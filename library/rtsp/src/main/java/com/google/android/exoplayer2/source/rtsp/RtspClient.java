/*
 * Copyright 2021 The Android Open Source Project
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
package com.google.android.exoplayer2.source.rtsp;

import static com.google.android.exoplayer2.source.rtsp.RtspMessageChannel.DEFAULT_RTSP_PORT;
import static com.google.android.exoplayer2.source.rtsp.RtspRequest.METHOD_ANNOUNCE;
import static com.google.android.exoplayer2.source.rtsp.RtspRequest.METHOD_DESCRIBE;
import static com.google.android.exoplayer2.source.rtsp.RtspRequest.METHOD_GET_PARAMETER;
import static com.google.android.exoplayer2.source.rtsp.RtspRequest.METHOD_OPTIONS;
import static com.google.android.exoplayer2.source.rtsp.RtspRequest.METHOD_PAUSE;
import static com.google.android.exoplayer2.source.rtsp.RtspRequest.METHOD_PLAY;
import static com.google.android.exoplayer2.source.rtsp.RtspRequest.METHOD_PLAY_NOTIFY;
import static com.google.android.exoplayer2.source.rtsp.RtspRequest.METHOD_RECORD;
import static com.google.android.exoplayer2.source.rtsp.RtspRequest.METHOD_REDIRECT;
import static com.google.android.exoplayer2.source.rtsp.RtspRequest.METHOD_SETUP;
import static com.google.android.exoplayer2.source.rtsp.RtspRequest.METHOD_SET_PARAMETER;
import static com.google.android.exoplayer2.source.rtsp.RtspRequest.METHOD_TEARDOWN;
import static com.google.android.exoplayer2.source.rtsp.RtspRequest.METHOD_UNSET;
import static com.google.android.exoplayer2.util.Assertions.checkArgument;
import static com.google.android.exoplayer2.util.Assertions.checkNotNull;
import static com.google.android.exoplayer2.util.Assertions.checkState;
import static com.google.android.exoplayer2.util.Assertions.checkStateNotNull;
import static com.google.common.base.Strings.nullToEmpty;
import static java.lang.Math.max;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.SparseArray;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ParserException;
import com.google.android.exoplayer2.source.rtsp.RtspMediaPeriod.RtpLoadInfo;
import com.google.android.exoplayer2.source.rtsp.RtspMediaSource.RtspPlaybackException;
import com.google.android.exoplayer2.source.rtsp.RtspMessageChannel.InterleavedBinaryDataListener;
import com.google.android.exoplayer2.source.rtsp.RtspMessageUtil.RtspAuthUserInfo;
import com.google.android.exoplayer2.source.rtsp.RtspMessageUtil.RtspSessionHeader;

import com.google.android.exoplayer2.util.Util;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import java.io.Closeable;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.net.SocketFactory;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** The RTSP client. */
final class RtspClient implements Closeable {

  /**
   * The RTSP session state (RFC2326, Section A.1). One of {@link #RTSP_STATE_UNINITIALIZED}, {@link
   * #RTSP_STATE_INIT}, {@link #RTSP_STATE_READY}, or {@link #RTSP_STATE_PLAYING}.
   */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @IntDef({RTSP_STATE_UNINITIALIZED, RTSP_STATE_INIT, RTSP_STATE_READY, RTSP_STATE_PLAYING})
  public @interface RtspState {}
  /** RTSP uninitialized state, the state before sending any SETUP request. */
  public static final int RTSP_STATE_UNINITIALIZED = -1;
  /** RTSP initial state, the state after sending SETUP REQUEST. */
  public static final int RTSP_STATE_INIT = 0;
  /** RTSP ready state, the state after receiving SETUP, or PAUSE response. */
  public static final int RTSP_STATE_READY = 1;
  /** RTSP playing state, the state after receiving PLAY response. */
  public static final int RTSP_STATE_PLAYING = 2;

  private static final long DEFAULT_RTSP_KEEP_ALIVE_INTERVAL_MS = 30_000;

  String TAG = Constants.TAG + " RtspClient";

  /** A listener for session information update. */
  public interface SessionInfoListener {
    /** Called when the session information is available. */
    void onSessionTimelineUpdated(RtspSessionTiming timing, ImmutableList<RtspMediaTrack> tracks);
    /**
     * Called when failed to get session information from the RTSP server, or when error happened
     * during updating the session timeline.
     */
    void onSessionTimelineRequestFailed(String message, @Nullable Throwable cause);
  }

  /** A listener for playback events. */
  public interface PlaybackEventListener {
    /** Called when setup is completed and playback can start. */
    void onRtspSetupCompleted();

    /**
     * Called when a PLAY request is acknowledged by the server and playback can start.
     *
     * @param startPositionUs The server-supplied start position in microseconds.
     * @param trackTimingList The list of {@link RtspTrackTiming} for the playing tracks.
     */
    void onPlaybackStarted(long startPositionUs, ImmutableList<RtspTrackTiming> trackTimingList);

    /** Called when errors are encountered during playback. */
    void onPlaybackError(RtspPlaybackException error);
  }

  private final SessionInfoListener sessionInfoListener;
  private final PlaybackEventListener playbackEventListener;
  private final String userAgent;
  private final boolean debugLoggingEnabled;
  private final ArrayDeque<RtpLoadInfo> pendingSetupRtpLoadInfos;
  // TODO(b/172331505) Add a timeout monitor for pending requests.
  private final SparseArray<RtspRequest> pendingRequests;
  private final MessageSender messageSender;

  /** RTSP session URI. */
  private Uri uri;

  private RtspMessageChannel messageChannel;
  @Nullable private RtspAuthUserInfo rtspAuthUserInfo;
  @Nullable private String sessionId;
  @Nullable private RtspAuthenticationInfo rtspAuthenticationInfo;
  @RtspState private int rtspState;
  private boolean hasUpdatedTimelineAndTracks;
  private boolean receivedAuthorizationRequest;
  private long pendingSeekPositionUs;
  public RtspDescribeResponse test;

  /**
   * Creates a new instance.
   *
   * <p>The constructor must be called on the playback thread. The thread is also where {@link
   * SessionInfoListener} and {@link PlaybackEventListener} events are sent. User must {@link
   * #start} the client, and {@link #close} it when done.
   *
   * <p>Note: all method invocations must be made from the playback thread.
   *
   * @param sessionInfoListener The {@link SessionInfoListener}.
   * @param playbackEventListener The {@link PlaybackEventListener}.
   * @param userAgent The user agent.
   * @param uri The RTSP playback URI.
   */
  public RtspClient(
      SessionInfoListener sessionInfoListener,
      PlaybackEventListener playbackEventListener,
      String userAgent,
      Uri uri,
      boolean debugLoggingEnabled) {
    Log.i(TAG,"Constructor" );
    this.sessionInfoListener = sessionInfoListener;
    this.playbackEventListener = playbackEventListener;
    this.userAgent = userAgent;
    this.debugLoggingEnabled = debugLoggingEnabled;
    this.pendingSetupRtpLoadInfos = new ArrayDeque<>();
    this.pendingRequests = new SparseArray<>();
    this.messageSender = new MessageSender();
    this.uri = RtspMessageUtil.removeUserInfo(uri);
    this.messageChannel = new RtspMessageChannel(new MessageListener());
    this.rtspAuthUserInfo = RtspMessageUtil.parseUserInfo(uri);
    this.pendingSeekPositionUs = C.TIME_UNSET;
    this.rtspState = RTSP_STATE_UNINITIALIZED;
    Log.i(TAG,"this.uri " + this.uri);

  }

  /**
   * Starts the client and sends an DESCRIBE request.
   *
   * <p>Calls {@link #close()} if {@link IOException} is thrown when opening a connection to the
   * supplied {@link Uri}.
   *
   * @throws IOException When failed to open a connection to the supplied {@link Uri}.
   */
  public void start() throws IOException {
    Log.i(TAG,"start()" );
    try {
      messageChannel.open(getSocket(uri));
    } catch (IOException e) {
      Util.closeQuietly(messageChannel);
      throw e;
    }
    //Skip OptionsRequest
    //Skip DescribeRequest
    // I made this param so that we can set a watch point for the 'test' var
    this.test = new RtspDescribeResponse( 200, SessionDescriptionParser.customCreateDescription() );
    customDescribe(this.test);
  }

  private void customDescribe(RtspDescribeResponse response) {
    Log.i(TAG, "customDescribe");
    RtspSessionTiming sessionTiming = RtspSessionTiming.DEFAULT;
    @Nullable
    String sessionRangeAttributeString =
        response.sessionDescription.attributes.get(SessionDescription.ATTR_RANGE);
    if (sessionRangeAttributeString != null) {
      try {
        sessionTiming = RtspSessionTiming.parseTiming(sessionRangeAttributeString);
      } catch (ParserException e) {
        sessionInfoListener.onSessionTimelineRequestFailed("SDP format error.", /* cause= */ e);
        return;
      }
    }

    ImmutableList<RtspMediaTrack> tracks = buildTrackList(response.sessionDescription, uri);
    if (tracks.isEmpty()) {
      sessionInfoListener.onSessionTimelineRequestFailed("No playable track.", /* cause= */ null);
      return;
    }

    sessionInfoListener.onSessionTimelineUpdated(sessionTiming, tracks);
    hasUpdatedTimelineAndTracks = true;
  }


  /** Returns the current {@link RtspState RTSP state}. */
  @RtspState
  public int getState() {
    Log.i(TAG,"getState()" );
    return rtspState;
  }

  /**
   * Triggers RTSP SETUP requests after track selection.
   *
   * <p>All selected tracks (represented by {@link RtpLoadInfo}) must have valid transport.
   *
   * @param loadInfos A list of selected tracks represented by {@link RtpLoadInfo}.
   */
  public void setupSelectedTracks(List<RtpLoadInfo> loadInfos) {
    Log.i(TAG,"setupSelectedTracks()" );
    pendingSetupRtpLoadInfos.addAll(loadInfos);
    continueSetupRtspTrack();
  }

  /**
   * Starts RTSP playback by sending RTSP PLAY request.
   *
   * @param offsetMs The playback offset in milliseconds, with respect to the stream start position.
   */
  public void startPlayback(long offsetMs) {
    Log.i(TAG,"startPlayback()" );
    messageSender.sendPlayRequest(uri, offsetMs, checkNotNull(sessionId));
  }


  @Override
  public void close() throws IOException {
    Log.i(TAG,"close()" );
    messageChannel.close();
  }

  /**
   * Sets up a new playback session using TCP as RTP lower transport.
   *
   * <p>This mode is also known as "RTP-over-RTSP".
   */
  public void retryWithRtpTcp() {
    Log.i(TAG,"retryWithRtpTcp()" );
    try {
      close();
      messageChannel = new RtspMessageChannel(new MessageListener());
      messageChannel.open(getSocket(uri));
      sessionId = null;
      receivedAuthorizationRequest = false;
      rtspAuthenticationInfo = null;
    } catch (IOException e) {
      playbackEventListener.onPlaybackError(new RtspPlaybackException(e));
    }
  }

  /** Registers an {@link InterleavedBinaryDataListener} to receive RTSP interleaved data. */
  public void registerInterleavedDataChannel(
      int channel, InterleavedBinaryDataListener interleavedBinaryDataListener) {
    messageChannel.registerInterleavedBinaryDataListener(channel, interleavedBinaryDataListener);
  }

  private void continueSetupRtspTrack() {
    @Nullable RtpLoadInfo loadInfo = pendingSetupRtpLoadInfos.pollFirst();
    if (loadInfo == null) {
      playbackEventListener.onRtspSetupCompleted();
      return;
    }
    //TODO: Remove this
    messageSender.sendSetupRequest(loadInfo.getTrackUri(), loadInfo.getTransport(), sessionId);
    //RtspSessionHeader sessionHeader = new RtspSessionHeader("1988052153", 60000);
    //customSetup(new RtspSetupResponse(200, sessionHeader, "RTP/AVP;unicast;client_port=37620-37621;source=34.227.104.115;server_port=7144-7145;ssrc=456994E4"));
  }

  private void customSetup(RtspSetupResponse response) {
    checkState(rtspState != RTSP_STATE_UNINITIALIZED);
    Log.i(TAG, "onSetupResponseReceived");
    rtspState = RTSP_STATE_READY;
    sessionId = response.sessionHeader.sessionId;
    continueSetupRtspTrack();
  }

  /** Returns a {@link Socket} that is connected to the {@code uri}. */
  private static Socket getSocket(Uri uri) throws IOException {
    checkArgument(uri.getHost() != null);
    int rtspPort = uri.getPort() > 0 ? uri.getPort() : DEFAULT_RTSP_PORT;
    return SocketFactory.getDefault().createSocket(checkNotNull(uri.getHost()), rtspPort);
  }

  private void dispatchRtspError(Throwable error) {
    RtspPlaybackException playbackException =
        error instanceof RtspPlaybackException
            ? (RtspPlaybackException) error
            : new RtspPlaybackException(error);

    if (hasUpdatedTimelineAndTracks) {
      // Playback event listener must be non-null after timeline has been updated.
      playbackEventListener.onPlaybackError(playbackException);
    } else {
      sessionInfoListener.onSessionTimelineRequestFailed(nullToEmpty(error.getMessage()), error);
    }
  }

  /**
   * Returns whether the RTSP server supports the DESCRIBE method.
   *
   * <p>The DESCRIBE method is marked "recommended to implement" in RFC2326 Section 10. We assume
   * the server supports DESCRIBE, if the OPTIONS response does not include a PUBLIC header.
   *
   * @param serverSupportedMethods A list of RTSP methods (as defined in RFC2326 Section 10, encoded
   *     as {@link RtspRequest.Method}) that are supported by the RTSP server.
   */
  private static boolean serverSupportsDescribe(List<Integer> serverSupportedMethods) {
    return serverSupportedMethods.isEmpty() || serverSupportedMethods.contains(METHOD_DESCRIBE);
  }

  /**
   * Gets the included {@link RtspMediaTrack RtspMediaTracks} from a {@link SessionDescription}.
   *
   * @param sessionDescription The {@link SessionDescription}.
   * @param uri The RTSP playback URI.
   */
  private static ImmutableList<RtspMediaTrack> buildTrackList(
      SessionDescription sessionDescription, Uri uri) {
    ImmutableList.Builder<RtspMediaTrack> trackListBuilder = new ImmutableList.Builder<>();
    for (int i = 0; i < sessionDescription.mediaDescriptionList.size(); i++) {
      MediaDescription mediaDescription = sessionDescription.mediaDescriptionList.get(i);
      // Includes tracks with supported formats only.
      if (RtpPayloadFormat.isFormatSupported(mediaDescription)) {
        trackListBuilder.add(new RtspMediaTrack(mediaDescription, uri));
      }
    }
    return trackListBuilder.build();
  }

  private final class MessageSender {

    private int cSeq;
    private @MonotonicNonNull RtspRequest lastRequest;

    public void sendSetupRequest(Uri trackUri, String transport, @Nullable String sessionId) {
      //TODO: Remove this
      rtspState = RTSP_STATE_INIT;
      sendRequest(
          getRequestWithCommonHeaders(
              METHOD_SETUP,
              sessionId,
              /* additionalHeaders= */ ImmutableMap.of(RtspHeaders.TRANSPORT, transport),
              trackUri));
    }

    public void sendPlayRequest(Uri uri, long offsetMs, String sessionId) {
      checkState(rtspState == RTSP_STATE_READY || rtspState == RTSP_STATE_PLAYING);
      sendRequest(
          getRequestWithCommonHeaders(
              METHOD_PLAY,
              sessionId,
              /* additionalHeaders= */ ImmutableMap.of(
                  RtspHeaders.RANGE, RtspSessionTiming.getOffsetStartTimeTiming(offsetMs)),
              uri));
    }

    public void retryLastRequest() {
      checkStateNotNull(lastRequest);

      Multimap<String, String> headersMultiMap = lastRequest.headers.asMultiMap();
      Map<String, String> lastRequestHeaders = new HashMap<>();
      for (String headerName : headersMultiMap.keySet()) {
        if (headerName.equals(RtspHeaders.CSEQ)
            || headerName.equals(RtspHeaders.USER_AGENT)
            || headerName.equals(RtspHeaders.SESSION)
            || headerName.equals(RtspHeaders.AUTHORIZATION)) {
          // Clear session-specific header values.
          continue;
        }
        // Only include the header value that is written most recently.
        lastRequestHeaders.put(headerName, Iterables.getLast(headersMultiMap.get(headerName)));
      }

      sendRequest(
          getRequestWithCommonHeaders(
              lastRequest.method, sessionId, lastRequestHeaders, lastRequest.uri));
    }

    public void sendMethodNotAllowedResponse(int cSeq) {
      // RTSP status code 405: Method Not Allowed (RFC2326 Section 7.1.1).
      sendResponse(
          new RtspResponse(
              /* status= */ 405, new RtspHeaders.Builder(userAgent, sessionId, cSeq).build()));

      // The server could send a cSeq that is larger than the current stored cSeq. To maintain a
      // monotonically increasing cSeq number, this.cSeq needs to be reset to server's cSeq + 1.
      this.cSeq = max(this.cSeq, cSeq + 1);
    }

    private RtspRequest getRequestWithCommonHeaders(
        @RtspRequest.Method int method,
        @Nullable String sessionId,
        Map<String, String> additionalHeaders,
        Uri uri) {
      RtspHeaders.Builder headersBuilder = new RtspHeaders.Builder(userAgent, sessionId, cSeq++);

      if (rtspAuthenticationInfo != null) {
        checkStateNotNull(rtspAuthUserInfo);
        try {
          headersBuilder.add(
              RtspHeaders.AUTHORIZATION,
              rtspAuthenticationInfo.getAuthorizationHeaderValue(rtspAuthUserInfo, uri, method));
        } catch (ParserException e) {
          dispatchRtspError(new RtspPlaybackException(e));
        }
      }

      headersBuilder.addAll(additionalHeaders);
      return new RtspRequest(uri, method, headersBuilder.build(), /* messageBody= */ "");
    }

    private void sendRequest(RtspRequest request) {
      int cSeq = Integer.parseInt(checkNotNull(request.headers.get(RtspHeaders.CSEQ)));
      checkState(pendingRequests.get(cSeq) == null);
      pendingRequests.append(cSeq, request);
      List<String> message = RtspMessageUtil.serializeRequest(request);
      messageChannel.send(message);
      lastRequest = request;
    }

    private void sendResponse(RtspResponse response) {
      List<String> message = RtspMessageUtil.serializeResponse(response);
      messageChannel.send(message);
    }
  }

  private final class MessageListener implements RtspMessageChannel.MessageListener {

    private final Handler messageHandler;

    /**
     * Creates a new instance.
     *
     * <p>The constructor must be called on a {@link Looper} thread, on which all the received RTSP
     * messages are processed.
     */
    public MessageListener() {
      messageHandler = Util.createHandlerForCurrentLooper();
    }

    @Override
    public void onRtspMessageReceived(List<String> message) {
      Log.i(TAG, " onRtspMessageReceived() "+ message);
      messageHandler.post(() -> handleRtspMessage(message));
    }

    private void handleRtspMessage(List<String> message) {
      if (RtspMessageUtil.isRtspResponse(message)) {
        handleRtspResponse(message);
      } else {
        handleRtspRequest(message);
      }
    }

    private void handleRtspRequest(List<String> message) {
      // Handling RTSP requests on the client is optional (RFC2326 Section 10). Decline all
      // requests with 'Method Not Allowed'.
      messageSender.sendMethodNotAllowedResponse(
          Integer.parseInt(
              checkNotNull(RtspMessageUtil.parseRequest(message).headers.get(RtspHeaders.CSEQ))));
    }

    private void handleRtspResponse(List<String> message) {
      RtspResponse response = RtspMessageUtil.parseResponse(message);

      int cSeq = Integer.parseInt(checkNotNull(response.headers.get(RtspHeaders.CSEQ)));

      @Nullable RtspRequest matchingRequest = pendingRequests.get(cSeq);
      if (matchingRequest == null) {
        return;
      } else {
        pendingRequests.remove(cSeq);
      }

      @RtspRequest.Method int requestMethod = matchingRequest.method;

      try {
        switch (response.status) {
          case 200:
            break;
          case 301:
          case 302:
            // Redirection request.
            if (rtspState != RTSP_STATE_UNINITIALIZED) {
              rtspState = RTSP_STATE_INIT;
            }
            @Nullable String redirectionUriString = response.headers.get(RtspHeaders.LOCATION);
            if (redirectionUriString == null) {
              sessionInfoListener.onSessionTimelineRequestFailed(
                  "Redirection without new location.", /* cause= */ null);
            } else {
              Uri redirectionUri = Uri.parse(redirectionUriString);
              RtspClient.this.uri = RtspMessageUtil.removeUserInfo(redirectionUri);
              RtspClient.this.rtspAuthUserInfo = RtspMessageUtil.parseUserInfo(redirectionUri);
            }
            return;
          case 401:
            if (rtspAuthUserInfo != null && !receivedAuthorizationRequest) {
              // Unauthorized.
              @Nullable
              String wwwAuthenticateHeader = response.headers.get(RtspHeaders.WWW_AUTHENTICATE);
              if (wwwAuthenticateHeader == null) {
                throw ParserException.createForMalformedManifest(
                    "Missing WWW-Authenticate header in a 401 response.", /* cause= */ null);
              }
              rtspAuthenticationInfo =
                  RtspMessageUtil.parseWwwAuthenticateHeader(wwwAuthenticateHeader);
              messageSender.retryLastRequest();
              receivedAuthorizationRequest = true;
              return;
            }
            // fall through: if unauthorized and no userInfo present, or previous authentication
            // unsuccessful.
          default:
            dispatchRtspError(
                new RtspPlaybackException(
                    RtspMessageUtil.toMethodString(requestMethod) + " " + response.status));
            return;
        }

        switch (requestMethod) {
          case METHOD_SETUP:
            @Nullable String sessionHeaderString = response.headers.get(RtspHeaders.SESSION);
            @Nullable String transportHeaderString = response.headers.get(RtspHeaders.TRANSPORT);
            if (sessionHeaderString == null || transportHeaderString == null) {
              throw ParserException.createForMalformedManifest(
                  "Missing mandatory session or transport header", /* cause= */ null);
            }
            RtspSessionHeader sessionHeader = RtspMessageUtil.parseSessionHeader(sessionHeaderString);
            onSetupResponseReceived(new RtspSetupResponse(response.status, sessionHeader, transportHeaderString));

            break;

          case METHOD_PLAY:
            // Range header is optional for a PLAY response (RFC2326 Section 12).
            @Nullable String startTimingString = response.headers.get(RtspHeaders.RANGE);
            RtspSessionTiming timing =
                startTimingString == null
                    ? RtspSessionTiming.DEFAULT
                    : RtspSessionTiming.parseTiming(startTimingString);
            @Nullable String rtpInfoString = response.headers.get(RtspHeaders.RTP_INFO);
            ImmutableList<RtspTrackTiming> trackTimingList =
                rtpInfoString == null
                    ? ImmutableList.of()
                    : RtspTrackTiming.parseTrackTiming(rtpInfoString, uri);
            onPlayResponseReceived(new RtspPlayResponse(response.status, timing, trackTimingList));
            break;
          default:
            throw new IllegalStateException();
        }
      } catch (ParserException e) {
        dispatchRtspError(new RtspPlaybackException(e));
      }
    }

    // Response handlers must only be called only on 200 (OK) responses.

    private void onSetupResponseReceived(RtspSetupResponse response) {
      checkState(rtspState != RTSP_STATE_UNINITIALIZED);
      Log.i(TAG, "onSetupResponseReceived");

      rtspState = RTSP_STATE_READY;
      sessionId = response.sessionHeader.sessionId;
      continueSetupRtspTrack();
    }

    private void onPlayResponseReceived(RtspPlayResponse response) {
      Log.i(TAG, "onPlayResponseReceived " + " checkState = " + rtspState);
      checkState(rtspState == RTSP_STATE_READY);

      rtspState = RTSP_STATE_PLAYING;
      playbackEventListener.onPlaybackStarted(
          Util.msToUs(response.sessionTiming.startTimeMs), response.trackTimingList);
      pendingSeekPositionUs = C.TIME_UNSET;
    }

  }

}
