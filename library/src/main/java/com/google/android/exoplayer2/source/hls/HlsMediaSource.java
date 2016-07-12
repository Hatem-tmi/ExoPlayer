/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.source.hls;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.ParserException;
import com.google.android.exoplayer2.TrackSelection;
import com.google.android.exoplayer2.source.AdaptiveMediaSourceEventListener;
import com.google.android.exoplayer2.source.AdaptiveMediaSourceEventListener.EventDispatcher;
import com.google.android.exoplayer2.source.CompositeSequenceableLoader;
import com.google.android.exoplayer2.source.MediaPeriod;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.SampleStream;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.chunk.FormatEvaluator;
import com.google.android.exoplayer2.source.hls.playlist.HlsMasterPlaylist;
import com.google.android.exoplayer2.source.hls.playlist.HlsMediaPlaylist;
import com.google.android.exoplayer2.source.hls.playlist.HlsPlaylist;
import com.google.android.exoplayer2.source.hls.playlist.HlsPlaylistParser;
import com.google.android.exoplayer2.source.hls.playlist.Variant;
import com.google.android.exoplayer2.upstream.Allocator;
import com.google.android.exoplayer2.upstream.BandwidthMeter;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSourceFactory;
import com.google.android.exoplayer2.upstream.Loader;
import com.google.android.exoplayer2.upstream.ParsingLoadable;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.MimeTypes;

import android.net.Uri;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Pair;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * An HLS {@link MediaSource}.
 */
public final class HlsMediaSource implements MediaPeriod, MediaSource,
    Loader.Callback<ParsingLoadable<HlsPlaylist>>, HlsSampleStreamWrapper.Callback {

  /**
   * The default minimum number of times to retry loading data prior to failing.
   */
  public static final int DEFAULT_MIN_LOADABLE_RETRY_COUNT = 3;

  private final Uri manifestUri;
  private final DataSourceFactory dataSourceFactory;
  private final BandwidthMeter bandwidthMeter;
  private final int minLoadableRetryCount;
  private final EventDispatcher eventDispatcher;
  private final IdentityHashMap<SampleStream, HlsSampleStreamWrapper> sampleStreamSources;
  private final PtsTimestampAdjusterProvider timestampAdjusterProvider;
  private final HlsPlaylistParser manifestParser;

  private DataSource manifestDataSource;
  private Loader manifestFetcher;

  private Callback callback;
  private Allocator allocator;
  private long preparePositionUs;
  private int pendingPrepareCount;

  private boolean seenFirstTrackSelection;
  private long durationUs;
  private boolean isLive;
  private TrackGroupArray trackGroups;
  private int[] selectedTrackCounts;
  private HlsSampleStreamWrapper[] sampleStreamWrappers;
  private HlsSampleStreamWrapper[] enabledSampleStreamWrappers;
  private CompositeSequenceableLoader sequenceableLoader;

  public HlsMediaSource(Uri manifestUri, DataSourceFactory dataSourceFactory,
      BandwidthMeter bandwidthMeter, Handler eventHandler,
      AdaptiveMediaSourceEventListener eventListener) {
    this(manifestUri, dataSourceFactory, bandwidthMeter, DEFAULT_MIN_LOADABLE_RETRY_COUNT,
        eventHandler, eventListener);
  }

  public HlsMediaSource(Uri manifestUri, DataSourceFactory dataSourceFactory,
      BandwidthMeter bandwidthMeter, int minLoadableRetryCount, Handler eventHandler,
      AdaptiveMediaSourceEventListener eventListener) {
    this.manifestUri = manifestUri;
    this.dataSourceFactory = dataSourceFactory;
    this.bandwidthMeter = bandwidthMeter;
    this.minLoadableRetryCount = minLoadableRetryCount;
    eventDispatcher = new EventDispatcher(eventHandler, eventListener);

    sampleStreamSources = new IdentityHashMap<>();
    timestampAdjusterProvider = new PtsTimestampAdjusterProvider();
    manifestParser = new HlsPlaylistParser();
  }

  // MediaSource implementation.

  @Override
  public int getPeriodCount() {
    return 1;
  }

  @Override
  public MediaPeriod createPeriod(int index) {
    Assertions.checkArgument(index == 0);
    return this;
  }

  // MediaPeriod implementation.

  @Override
  public void prepare(Callback callback, Allocator allocator, long positionUs) {
    this.callback = callback;
    this.allocator = allocator;
    preparePositionUs = positionUs;
    manifestDataSource = dataSourceFactory.createDataSource();
    manifestFetcher = new Loader("Loader:ManifestFetcher");
    ParsingLoadable<HlsPlaylist> loadable = new ParsingLoadable<>(manifestDataSource, manifestUri,
        C.DATA_TYPE_MANIFEST, manifestParser);
    long elapsedRealtimeMs = manifestFetcher.startLoading(loadable, this, minLoadableRetryCount);
    eventDispatcher.loadStarted(loadable.dataSpec, loadable.type, elapsedRealtimeMs);
  }

  @Override
  public void maybeThrowPrepareError() throws IOException {
    if (sampleStreamWrappers == null) {
      manifestFetcher.maybeThrowError();
    } else {
      for (HlsSampleStreamWrapper sampleStreamWrapper : sampleStreamWrappers) {
        sampleStreamWrapper.maybeThrowPrepareError();
      }
    }
  }

  @Override
  public long getDurationUs() {
    return durationUs;
  }

  @Override
  public TrackGroupArray getTrackGroups() {
    return trackGroups;
  }

  @Override
  public SampleStream[] selectTracks(List<SampleStream> oldStreams,
      List<TrackSelection> newSelections, long positionUs) {
    SampleStream[] newStreams = new SampleStream[newSelections.size()];
    // Select tracks for each wrapper.
    int enabledSampleStreamWrapperCount = 0;
    for (int i = 0; i < sampleStreamWrappers.length; i++) {
      selectedTrackCounts[i] += selectTracks(sampleStreamWrappers[i], oldStreams, newSelections,
          newStreams);
      if (selectedTrackCounts[i] > 0) {
        enabledSampleStreamWrapperCount++;
      }
    }
    // Update the enabled wrappers.
    enabledSampleStreamWrappers = new HlsSampleStreamWrapper[enabledSampleStreamWrapperCount];
    sequenceableLoader = new CompositeSequenceableLoader(enabledSampleStreamWrappers);
    enabledSampleStreamWrapperCount = 0;
    for (int i = 0; i < sampleStreamWrappers.length; i++) {
      if (selectedTrackCounts[i] > 0) {
        enabledSampleStreamWrappers[enabledSampleStreamWrapperCount++] = sampleStreamWrappers[i];
      }
    }
    if (enabledSampleStreamWrapperCount > 0 && seenFirstTrackSelection
        && !newSelections.isEmpty()) {
      seekToUs(positionUs);
    }
    seenFirstTrackSelection = true;
    return newStreams;
  }

  @Override
  public boolean continueLoading(long positionUs) {
    return sequenceableLoader.continueLoading(positionUs);
  }

  @Override
  public long getNextLoadPositionUs() {
    return sequenceableLoader.getNextLoadPositionUs();
  }

  @Override
  public long readDiscontinuity() {
    return C.UNSET_TIME_US;
  }

  @Override
  public long getBufferedPositionUs() {
    long bufferedPositionUs = Long.MAX_VALUE;
    for (HlsSampleStreamWrapper sampleStreamWrapper : enabledSampleStreamWrappers) {
      long rendererBufferedPositionUs = sampleStreamWrapper.getBufferedPositionUs();
      if (rendererBufferedPositionUs != C.END_OF_SOURCE_US) {
        bufferedPositionUs = Math.min(bufferedPositionUs, rendererBufferedPositionUs);
      }
    }
    return bufferedPositionUs == Long.MAX_VALUE ? C.END_OF_SOURCE_US : bufferedPositionUs;
  }

  @Override
  public long seekToUs(long positionUs) {
    // Treat all seeks into non-seekable media as being to t=0.
    positionUs = isLive ? 0 : positionUs;
    timestampAdjusterProvider.reset();
    for (HlsSampleStreamWrapper sampleStreamWrapper : enabledSampleStreamWrappers) {
      sampleStreamWrapper.seekTo(positionUs);
    }
    return positionUs;
  }

  @Override
  public void release() {
    sampleStreamSources.clear();
    timestampAdjusterProvider.reset();
    manifestDataSource = null;
    if (manifestFetcher != null) {
      manifestFetcher.release();
      manifestFetcher = null;
    }
    callback = null;
    allocator = null;
    preparePositionUs = 0;
    pendingPrepareCount = 0;
    seenFirstTrackSelection = false;
    durationUs = 0;
    isLive = false;
    trackGroups = null;
    selectedTrackCounts = null;
    if (sampleStreamWrappers != null) {
      for (HlsSampleStreamWrapper sampleStreamWrapper : sampleStreamWrappers) {
        sampleStreamWrapper.release();
      }
      sampleStreamWrappers = null;
    }
    enabledSampleStreamWrappers = null;
    sequenceableLoader = null;
  }

  // Loader.Callback implementation.

  @Override
  public void onLoadCompleted(ParsingLoadable<HlsPlaylist> loadable, long elapsedRealtimeMs,
      long loadDurationMs) {
    eventDispatcher.loadCompleted(loadable.dataSpec, loadable.type, elapsedRealtimeMs,
        loadDurationMs, loadable.bytesLoaded());
    HlsPlaylist playlist = loadable.getResult();
    List<HlsSampleStreamWrapper> sampleStreamWrapperList = buildSampleStreamWrappers(playlist);
    sampleStreamWrappers = new HlsSampleStreamWrapper[sampleStreamWrapperList.size()];
    sampleStreamWrapperList.toArray(sampleStreamWrappers);
    selectedTrackCounts = new int[sampleStreamWrappers.length];
    pendingPrepareCount = sampleStreamWrappers.length;
    for (HlsSampleStreamWrapper sampleStreamWrapper : sampleStreamWrappers) {
      sampleStreamWrapper.prepare();
    }
  }

  @Override
  public void onLoadCanceled(ParsingLoadable<HlsPlaylist> loadable, long elapsedRealtimeMs,
      long loadDurationMs, boolean released) {
    eventDispatcher.loadCompleted(loadable.dataSpec, loadable.type, elapsedRealtimeMs,
        loadDurationMs, loadable.bytesLoaded());
  }

  @Override
  public int onLoadError(ParsingLoadable<HlsPlaylist> loadable, long elapsedRealtimeMs,
      long loadDurationMs, IOException error) {
    boolean isFatal = error instanceof ParserException;
    eventDispatcher.loadError(loadable.dataSpec, loadable.type, elapsedRealtimeMs, loadDurationMs,
        loadable.bytesLoaded(), error, isFatal);
    return isFatal ? Loader.DONT_RETRY_FATAL : Loader.RETRY;
  }

  // HlsSampleStreamWrapper.Callback implementation.

  @Override
  public void onPrepared() {
    if (--pendingPrepareCount > 0) {
      return;
    }

    // The wrapper at index 0 is the one of type TRACK_TYPE_DEFAULT.
    durationUs = sampleStreamWrappers[0].getDurationUs();
    isLive = sampleStreamWrappers[0].isLive();

    int totalTrackGroupCount = 0;
    for (HlsSampleStreamWrapper sampleStreamWrapper : sampleStreamWrappers) {
      totalTrackGroupCount += sampleStreamWrapper.getTrackGroups().length;
    }
    TrackGroup[] trackGroupArray = new TrackGroup[totalTrackGroupCount];
    int trackGroupIndex = 0;
    for (HlsSampleStreamWrapper sampleStreamWrapper : sampleStreamWrappers) {
      int wrapperTrackGroupCount = sampleStreamWrapper.getTrackGroups().length;
      for (int j = 0; j < wrapperTrackGroupCount; j++) {
        trackGroupArray[trackGroupIndex++] = sampleStreamWrapper.getTrackGroups().get(j);
      }
    }
    trackGroups = new TrackGroupArray(trackGroupArray);
    callback.onPeriodPrepared(this);
  }

  @Override
  public void onContinueLoadingRequested(HlsSampleStreamWrapper sampleStreamWrapper) {
    if (trackGroups == null) {
      // Still preparing.
      return;
    }
    callback.onContinueLoadingRequested(this);
  }

  // Internal methods.

  private List<HlsSampleStreamWrapper> buildSampleStreamWrappers(HlsPlaylist playlist) {
    ArrayList<HlsSampleStreamWrapper> sampleStreamWrappers = new ArrayList<>();
    String baseUri = playlist.baseUri;

    if (playlist instanceof HlsMediaPlaylist) {
      Format format = Format.createContainerFormat("0", MimeTypes.APPLICATION_M3U8, null,
          Format.NO_VALUE);
      Variant[] variants = new Variant[] {new Variant(playlist.baseUri, format, null)};
      sampleStreamWrappers.add(buildSampleStreamWrapper(C.TRACK_TYPE_DEFAULT, baseUri, variants,
          new FormatEvaluator.AdaptiveEvaluator(bandwidthMeter), null, null));
      return sampleStreamWrappers;
    }

    HlsMasterPlaylist masterPlaylist = (HlsMasterPlaylist) playlist;

    // Build the default stream wrapper.
    List<Variant> selectedVariants = new ArrayList<>(masterPlaylist.variants);
    ArrayList<Variant> definiteVideoVariants = new ArrayList<>();
    ArrayList<Variant> definiteAudioOnlyVariants = new ArrayList<>();
    for (int i = 0; i < selectedVariants.size(); i++) {
      Variant variant = selectedVariants.get(i);
      if (variant.format.height > 0 || variantHasExplicitCodecWithPrefix(variant, "avc")) {
        definiteVideoVariants.add(variant);
      } else if (variantHasExplicitCodecWithPrefix(variant, "mp4a")) {
        definiteAudioOnlyVariants.add(variant);
      }
    }
    if (!definiteVideoVariants.isEmpty()) {
      // We've identified some variants as definitely containing video. Assume variants within the
      // master playlist are marked consistently, and hence that we have the full set. Filter out
      // any other variants, which are likely to be audio only.
      selectedVariants = definiteVideoVariants;
    } else if (definiteAudioOnlyVariants.size() < selectedVariants.size()) {
      // We've identified some variants, but not all, as being audio only. Filter them out to leave
      // the remaining variants, which are likely to contain video.
      selectedVariants.removeAll(definiteAudioOnlyVariants);
    } else {
      // Leave the enabled variants unchanged. They're likely either all video or all audio.
    }
    if (!selectedVariants.isEmpty()) {
      Variant[] variants = new Variant[selectedVariants.size()];
      selectedVariants.toArray(variants);
      sampleStreamWrappers.add(buildSampleStreamWrapper(C.TRACK_TYPE_DEFAULT, baseUri, variants,
          new FormatEvaluator.AdaptiveEvaluator(bandwidthMeter), masterPlaylist.muxedAudioFormat,
          masterPlaylist.muxedCaptionFormat));
    }

    // Build the audio stream wrapper if applicable.
    List<Variant> audioVariants = masterPlaylist.audios;
    if (!audioVariants.isEmpty()) {
      Variant[] variants = new Variant[audioVariants.size()];
      audioVariants.toArray(variants);
      sampleStreamWrappers.add(buildSampleStreamWrapper(C.TRACK_TYPE_AUDIO, baseUri, variants, null,
          null, null));
    }

    // Build the text stream wrapper if applicable.
    List<Variant> subtitleVariants = masterPlaylist.subtitles;
    if (!subtitleVariants.isEmpty()) {
      Variant[] variants = new Variant[subtitleVariants.size()];
      subtitleVariants.toArray(variants);
      sampleStreamWrappers.add(buildSampleStreamWrapper(C.TRACK_TYPE_TEXT, baseUri, variants, null,
          null, null));
    }

    return sampleStreamWrappers;
  }

  private HlsSampleStreamWrapper buildSampleStreamWrapper(int trackType, String baseUri,
      Variant[] variants, FormatEvaluator formatEvaluator, Format muxedAudioFormat,
      Format muxedCaptionFormat) {
    DataSource dataSource = dataSourceFactory.createDataSource(bandwidthMeter);
    HlsChunkSource defaultChunkSource = new HlsChunkSource(baseUri, variants, dataSource,
        timestampAdjusterProvider, formatEvaluator);
    return new HlsSampleStreamWrapper(trackType, this, defaultChunkSource, allocator,
        preparePositionUs, muxedAudioFormat, muxedCaptionFormat, minLoadableRetryCount,
        eventDispatcher);
  }

  private int selectTracks(HlsSampleStreamWrapper sampleStreamWrapper,
      List<SampleStream> allOldStreams, List<TrackSelection> allNewSelections,
      SampleStream[] allNewStreams) {
    // Get the subset of the old streams for the source.
    ArrayList<SampleStream> oldStreams = new ArrayList<>();
    for (int i = 0; i < allOldStreams.size(); i++) {
      SampleStream stream = allOldStreams.get(i);
      if (sampleStreamSources.get(stream) == sampleStreamWrapper) {
        sampleStreamSources.remove(stream);
        oldStreams.add(stream);
      }
    }
    // Get the subset of the new selections for the wrapper.
    ArrayList<TrackSelection> newSelections = new ArrayList<>();
    int[] newSelectionOriginalIndices = new int[allNewSelections.size()];
    for (int i = 0; i < allNewSelections.size(); i++) {
      TrackSelection selection = allNewSelections.get(i);
      Pair<HlsSampleStreamWrapper, Integer> sourceAndGroup = getSourceAndGroup(selection.group);
      if (sourceAndGroup.first == sampleStreamWrapper) {
        newSelectionOriginalIndices[newSelections.size()] = i;
        newSelections.add(new TrackSelection(sourceAndGroup.second, selection.getTracks()));
      }
    }
    // Do nothing if nothing has changed, except during the first selection.
    if (seenFirstTrackSelection && oldStreams.isEmpty() && newSelections.isEmpty()) {
      return 0;
    }
    // Perform the selection.
    SampleStream[] newStreams = sampleStreamWrapper.selectTracks(oldStreams, newSelections,
        !seenFirstTrackSelection);
    for (int j = 0; j < newStreams.length; j++) {
      allNewStreams[newSelectionOriginalIndices[j]] = newStreams[j];
      sampleStreamSources.put(newStreams[j], sampleStreamWrapper);
    }
    return newSelections.size() - oldStreams.size();
  }

  private Pair<HlsSampleStreamWrapper, Integer> getSourceAndGroup(int group) {
    int totalTrackGroupCount = 0;
    for (HlsSampleStreamWrapper sampleStreamWrapper : sampleStreamWrappers) {
      int sourceTrackGroupCount = sampleStreamWrapper.getTrackGroups().length;
      if (group < totalTrackGroupCount + sourceTrackGroupCount) {
        return Pair.create(sampleStreamWrapper, group - totalTrackGroupCount);
      }
      totalTrackGroupCount += sourceTrackGroupCount;
    }
    throw new IndexOutOfBoundsException();
  }

  private static boolean variantHasExplicitCodecWithPrefix(Variant variant, String prefix) {
    String codecs = variant.codecs;
    if (TextUtils.isEmpty(codecs)) {
      return false;
    }
    String[] codecArray = codecs.split("(\\s*,\\s*)|(\\s*$)");
    for (String codec : codecArray) {
      if (codec.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

}
