/*
* Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.mediabrowserservice;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaBrowserServiceCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaButtonReceiver;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import com.example.android.mediabrowserservice.model.MusicProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.example.android.mediabrowserservice.model.MusicProvider.MEDIA_ID_EMPTY_ROOT;
import static com.example.android.mediabrowserservice.model.MusicProvider.MEDIA_ID_ROOT;

/**
 * This class provides a MediaBrowser through a service. It exposes the media library to a browsing
 * client, through the onGetRoot and onLoadChildren methods. It also creates a MediaSession and
 * exposes it through its MediaSession.Token, which allows the client to create a MediaController
 * that connects to and send control commands to the MediaSession remotely. This is useful for
 * user interfaces that need to interact with your media session, like Android Auto. You can
 * (should) also use the same service from your app's UI, which gives a seamless playback
 * experience to the user.
 * <p>
 * To implement a MediaBrowserService, you need to:
 * <p>
 * <ul>
 * <p>
 * <li> Extend {@link android.support.v4.media.MediaBrowserServiceCompat}, implementing the media
 * browsing related methods {@link android.support.v4.media.MediaBrowserServiceCompat#onGetRoot} and
 * {@link android.support.v4.media.MediaBrowserServiceCompat#onLoadChildren};
 * <li> In onCreate, start a new {@link android.support.v4.media.session.MediaSessionCompat} and
 * notify its parent with the session's token
 * {@link android.support.v4.media.MediaBrowserServiceCompat#setSessionToken};
 * <p>
 * <li> Set a callback on the
 * {@link android.support.v4.media.session.MediaSessionCompat#setCallback(MediaSessionCompat.Callback)}.
 * The callback will receive all the user's actions, like play, pause, etc;
 * <p>
 * <li> Handle all the actual music playing using any method your app prefers (for example,
 * {@link android.media.MediaPlayer})
 * <p>
 * <li> Update playbackState, "now playing" metadata and queue, using MediaSession proper methods
 * {@link android.support.v4.media.session.MediaSessionCompat#setPlaybackState(PlaybackStateCompat)}
 * {@link android.support.v4.media.session.MediaSessionCompat#setMetadata(MediaMetadataCompat)} and
 * if your implementation allows it,
 * {@link android.support.v4.media.session.MediaSessionCompat#setQueue(List)})
 * <p>
 * <li> Declare and export the service in AndroidManifest with an intent receiver for the action
 * android.media.browse.MediaBrowserService
 * <li> Declare a broadcast receiver to receive media button events. This is required if your app
 * supports Android KitKat or previous:
 * &lt;receiver android:name="android.support.v4.media.session.MediaButtonReceiver"&gt;
 * &lt;intent-filter&gt;
 * &lt;action android:name="android.intent.action.MEDIA_BUTTON" /&gt;
 * &lt;/intent-filter&gt;
 * &lt;/receiver&gt;
 * <p>
 * </ul>
 * <p>
 * To make your app compatible with Android Auto, you also need to:
 * <p>
 * <ul>
 * <p>
 * <li> Declare a meta-data tag in AndroidManifest.xml linking to a xml resource
 * with a &lt;automotiveApp&gt; root element. For a media app, this must include
 * an &lt;uses name="media"/&gt; element as a child.
 * For example, in AndroidManifest.xml:
 * &lt;meta-data android:name="com.google.android.gms.car.application"
 * android:resource="@xml/automotive_app_desc"/&gt;
 * And in res/values/automotive_app_desc.xml:
 * &lt;automotiveApp&gt;
 * &lt;uses name="media"/&gt;
 * &lt;/automotiveApp&gt;
 * <p>
 * </ul>
 *
 * @see <a href="README.md">README.md</a> for more details.
 */

public class MusicService extends MediaBrowserServiceCompat {
    private static final String TAG = MusicService.class.getSimpleName();

    // ID for our MediaNotification.
    public static final int NOTIFICATION_ID = 412;

    // Request code for starting the UI.
    private static final int REQUEST_CODE = 99;

    // Delay stopSelf by using a handler.
    private static final long STOP_DELAY = TimeUnit.SECONDS.toMillis(30);
    private static final int STOP_CMD = 0x7c48;

    private MusicProvider mMusicProvider;
    private MediaSessionCompat mSession;
    public NotificationManagerCompat mNotificationManager;
    // Indicates whether the service was started.
    private boolean mServiceStarted;
    private Playback mPlayback;
    private MediaSessionCompat.QueueItem mCurrentMedia;
    private AudioBecomingNoisyReceiver mAudioBecomingNoisyReceiver;

    /**
     * Custom {@link Handler} to process the delayed stop command.
     */
    private Handler mDelayedStopHandler = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            if (msg == null || msg.what != STOP_CMD) {
                return false;
            }

            if (!mPlayback.isPlaying()) {
                Log.d(TAG, "Stopping service");
                stopSelf();
                mServiceStarted = false;
            }
            return false;
        }
    });

    /*
     * (non-Javadoc)
     * @see android.app.Service#onCreate()
     */
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");

        mMusicProvider = new MusicProvider();

        // Start a new MediaSession.
        mSession = new MediaSessionCompat(this, TAG);
        setSessionToken(mSession.getSessionToken());
        mSession.setCallback(new MediaSessionCallback());
        mSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

        mPlayback = new Playback(this, mMusicProvider);
        mPlayback.setCallback(new Playback.Callback() {
            @Override
            public void onPlaybackStatusChanged(int state) {
                updatePlaybackState(null);
            }

            @Override
            public void onCompletion() {
                // In this simple implementation there isn't a play queue, so we simply 'stop' after
                // the song is over.
                handleStopRequest();
            }

            @Override
            public void onError(String error) {
                updatePlaybackState(error);
            }
        });

        Context context = getApplicationContext();

        // This is an Intent to launch the app's UI, used primarily by the ongoing notification.
        Intent intent = new Intent(context, MusicPlayerActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        mSession.setSessionActivity(pi);

        mNotificationManager = NotificationManagerCompat.from(this);
        mAudioBecomingNoisyReceiver = new AudioBecomingNoisyReceiver(this);

        updatePlaybackState(null);
    }

    /**
     * (non-Javadoc)
     *
     * @see android.app.Service#onStartCommand(android.content.Intent, int, int)
     */
    @Override
    public int onStartCommand(Intent startIntent, int flags, int startId) {
        MediaButtonReceiver.handleIntent(mSession, startIntent);
        return super.onStartCommand(startIntent, flags, startId);
    }

    /**
     * (non-Javadoc)
     *
     * @see android.app.Service#onDestroy()
     */
    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        // Service is being killed, so make sure we release our resources
        handleStopRequest();

        mDelayedStopHandler.removeCallbacksAndMessages(null);
        // Always release the MediaSession to clean up resources
        // and notify associated MediaController(s).
        mSession.release();
    }

    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName,
                                 int clientUid, Bundle rootHints) {
        // Verify the client is authorized to browse media and return the root that
        // makes the most sense here. In this example we simply verify the package name
        // is the same as ours, but more complicated checks, and responses, are possible
        if (!clientPackageName.equals(getPackageName())) {
            // Allow the client to connect, but not browse, by returning an empty root
            return new BrowserRoot(MEDIA_ID_EMPTY_ROOT, null);
        }
        return new BrowserRoot(MEDIA_ID_ROOT, null);
    }

    @Override
    public void onLoadChildren(@NonNull final String parentMediaId,
                               @NonNull final Result<List<MediaBrowserCompat.MediaItem>> result) {
        Log.d(TAG, "OnLoadChildren: parentMediaId=" + parentMediaId);

        if (!mMusicProvider.isInitialized()) {
            // Use result.detach to allow calling result.sendResult from another thread:
            result.detach();

            mMusicProvider.retrieveMediaAsync(new MusicProvider.Callback() {
                @Override
                public void onMusicCatalogReady(boolean success) {
                    if (success) {
                        loadChildrenImpl(parentMediaId, result);
                    } else {
                        updatePlaybackState(getString(R.string.error_no_metadata));
                        result.sendResult(Collections.<MediaBrowserCompat.MediaItem>emptyList());
                    }
                }
            });

        } else {
            // If our music catalog is already loaded/cached, load them into result immediately
            loadChildrenImpl(parentMediaId, result);
        }
    }

    /**
     * Actual implementation of onLoadChildren that assumes that MusicProvider is already
     * initialized.
     */
    private void loadChildrenImpl(@NonNull final String parentMediaId,
                                  final Result<List<MediaBrowserCompat.MediaItem>> result) {
        List<MediaBrowserCompat.MediaItem> mediaItems = new ArrayList<>();

        switch (parentMediaId) {
            case MEDIA_ID_ROOT:
                for (MediaMetadataCompat track : mMusicProvider.getAllMusics()) {
                    MediaBrowserCompat.MediaItem bItem =
                            new MediaBrowserCompat.MediaItem(track.getDescription(),
                                    MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);
                    mediaItems.add(bItem);
                }
                break;
            case MEDIA_ID_EMPTY_ROOT:
                // Since the client provided the empty root we'll just send back an
                // empty list
                break;
            default:
                Log.w(TAG, "Skipping unmatched parentMediaId: " + parentMediaId);
                break;
        }
        result.sendResult(mediaItems);
    }

    private final class MediaSessionCallback extends MediaSessionCompat.Callback {

        @Override
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
            Log.d(TAG, "playFromMediaId mediaId:" + mediaId + "  extras=" + extras);

            // The mediaId used here is not the unique musicId. This one comes from the
            // MediaBrowser, and is actually a "hierarchy-aware mediaID": a concatenation of
            // the hierarchy in MediaBrowser and the actual unique musicID. This is necessary
            // so we can build the correct playing queue, based on where the track was
            // selected from.
            MediaMetadataCompat media = mMusicProvider.getMusic(mediaId);
            if (media != null) {
                mCurrentMedia =
                        new MediaSessionCompat.QueueItem(media.getDescription(), media.hashCode());

                // play the music
                handlePlayRequest();
            }
        }

        @Override
        public void onPlay() {
            Log.d(TAG, "play");

            if (mCurrentMedia != null) {
                handlePlayRequest();
            }
        }

        @Override
        public void onSeekTo(long position) {
            Log.d(TAG, "onSeekTo:" + position);
            mPlayback.seekTo((int) position);
        }

        @Override
        public void onPause() {
            Log.d(TAG, "pause. current state=" + mPlayback.getState());
            handlePauseRequest();
        }

        @Override
        public void onStop() {
            Log.d(TAG, "stop. current state=" + mPlayback.getState());
            handleStopRequest();
        }
    }

    /**
     * Handle a request to play music
     */
    private void handlePlayRequest() {
        Log.d(TAG, "handlePlayRequest: mState=" + mPlayback.getState());

        if (mCurrentMedia == null) {
            // Nothing to play
            return;
        }

        mDelayedStopHandler.removeCallbacksAndMessages(null);
        if (!mServiceStarted) {
            Log.v(TAG, "Starting service");
            // The MusicService needs to keep running even after the calling MediaBrowser
            // is disconnected. Call startService(Intent) and then stopSelf(..) when we no longer
            // need to play media.
            startService(new Intent(getApplicationContext(), MusicService.class));
            mServiceStarted = true;
        }

        if (!mSession.isActive()) {
            mSession.setActive(true);
        }

        updateMetadata();
        mPlayback.play(mCurrentMedia);
    }

    /**
     * Handle a request to pause music
     */
    private void handlePauseRequest() {
        Log.d(TAG, "handlePauseRequest: mState=" + mPlayback.getState());
        mPlayback.pause();

        // reset the delayed stop handler.
        mDelayedStopHandler.removeCallbacksAndMessages(null);
        mDelayedStopHandler.sendEmptyMessageDelayed(STOP_CMD, STOP_DELAY);
    }

    /**
     * Handle a request to stop music
     */
    private void handleStopRequest() {
        Log.d(TAG, "handleStopRequest: mState=" + mPlayback.getState());
        mPlayback.stop();
        // reset the delayed stop handler.
        mDelayedStopHandler.removeCallbacksAndMessages(null);
        mDelayedStopHandler.sendEmptyMessage(STOP_CMD);

        updatePlaybackState(null);
    }

    private void updateMetadata() {
        MediaSessionCompat.QueueItem queueItem = mCurrentMedia;
        String musicId = queueItem.getDescription().getMediaId();
        MediaMetadataCompat track = mMusicProvider.getMusic(musicId);

        final String trackId = track.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID);
        mSession.setMetadata(track);

        // Set the proper album artwork on the media session, so it can be shown in the
        // locked screen and in other places.
        if (track.getDescription().getIconBitmap() == null
                && track.getDescription().getIconUri() != null) {
            fetchArtwork(trackId, track.getDescription().getIconUri());
            postNotification();
        }
    }

    private void fetchArtwork(final String trackId, final Uri albumUri) {
        AlbumArtCache.getInstance().fetch(albumUri.toString(),
                new AlbumArtCache.FetchListener() {
                    @Override
                    public void onFetched(String artUrl, Bitmap bitmap, Bitmap icon) {
                        MediaSessionCompat.QueueItem queueItem = mCurrentMedia;
                        MediaMetadataCompat track = mMusicProvider.getMusic(trackId);
                        track = new MediaMetadataCompat.Builder(track)

                                // Set high resolution bitmap in METADATA_KEY_ALBUM_ART. This is
                                // used, for example, on the lockscreen background when the media
                                // session is active.
                                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)

                                // Set small version of the album art in the DISPLAY_ICON. This is
                                // used on the MediaDescription and thus it should be small to be
                                // serialized if necessary.
                                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, icon)

                                .build();

                        mMusicProvider.updateMusic(trackId, track);

                        // If we are still playing the same music
                        String currentPlayingId = queueItem.getDescription().getMediaId();
                        if (trackId.equals(currentPlayingId)) {
                            mSession.setMetadata(track);
                            postNotification();
                        }
                    }
                });
    }

    /**
     * Update the current media player state, optionally showing an error message.
     *
     * @param error if not null, error message to present to the user.
     */
    private void updatePlaybackState(String error) {
        Log.d(TAG, "updatePlaybackState, playback state=" + mPlayback.getState());
        long position = PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN;
        if (mPlayback != null && mPlayback.isConnected()) {
            position = mPlayback.getCurrentStreamPosition();
        }

        long playbackActions = PlaybackStateCompat.ACTION_PLAY
                | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID;
        if (mPlayback.isPlaying()) {
            playbackActions |= PlaybackStateCompat.ACTION_PAUSE;
        }

        PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder()
                .setActions(playbackActions);

        int state = mPlayback.getState();

        // If there is an error message, send it to the playback state:
        if (error != null) {
            // Error states are really only supposed to be used for errors that cause playback to
            // stop unexpectedly and persist until the user takes action to fix it.
            stateBuilder.setErrorMessage(error);
            state = PlaybackStateCompat.STATE_ERROR;
        }

        // Because the playback state is pulled from the Playback class lint thinks it may not
        // match permitted values.
        //noinspection WrongConstant
        stateBuilder.setState(state, position, 1.0f, SystemClock.elapsedRealtime());

        // Set the activeQueueItemId if the current index is valid.
        if (mCurrentMedia != null) {
            stateBuilder.setActiveQueueItemId(mCurrentMedia.getQueueId());
        }

        mSession.setPlaybackState(stateBuilder.build());

        if (state == PlaybackStateCompat.STATE_PLAYING) {
            Notification notification = postNotification();
            startForeground(NOTIFICATION_ID, notification);
            mAudioBecomingNoisyReceiver.register();
        } else {
            if (state == PlaybackStateCompat.STATE_PAUSED) {
                postNotification();
            } else {
                mNotificationManager.cancel(NOTIFICATION_ID);
            }
            stopForeground(false);
            mAudioBecomingNoisyReceiver.unregister();
        }
    }

    private Notification postNotification() {
        Notification notification = MediaNotificationHelper.createNotification(this, mSession);
        if (notification == null) {
            return null;
        }

        mNotificationManager.notify(NOTIFICATION_ID, notification);
        return notification;
    }

    /**
     * Implementation of the AudioManager.ACTION_AUDIO_BECOMING_NOISY Receiver.
     */

    private class AudioBecomingNoisyReceiver extends BroadcastReceiver {
        private final Context mContext;
        private boolean mIsRegistered = false;

        private IntentFilter mAudioNoisyIntentFilter =
                new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);

        protected AudioBecomingNoisyReceiver(Context context) {
            mContext = context.getApplicationContext();
        }

        public void register() {
            if (!mIsRegistered) {
                mContext.registerReceiver(this, mAudioNoisyIntentFilter);
                mIsRegistered = true;
            }
        }

        public void unregister() {
            if (mIsRegistered) {
                mContext.unregisterReceiver(this);
                mIsRegistered = false;
            }
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                handlePauseRequest();
            }
        }
    }
}
