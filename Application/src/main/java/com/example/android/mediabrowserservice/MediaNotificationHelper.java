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
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaButtonReceiver;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.support.v7.app.NotificationCompat;

/**
 * Helper class for building Media style Notifications from a
 * {@link android.support.v4.media.session.MediaSessionCompat}.
 */
public class MediaNotificationHelper {
    private MediaNotificationHelper() {
        // Helper utility class; do not instantiate.
    }

    public static Notification createNotification(Context context,
                                                  MediaSessionCompat mediaSession) {
        MediaControllerCompat controller = mediaSession.getController();
        MediaMetadataCompat mMetadata = controller.getMetadata();
        PlaybackStateCompat mPlaybackState = controller.getPlaybackState();

        if (mMetadata == null || mPlaybackState == null) {
            return null;
        }

        boolean isPlaying = mPlaybackState.getState() == PlaybackStateCompat.STATE_PLAYING;
        NotificationCompat.Action action = isPlaying
                ? new NotificationCompat.Action(R.drawable.ic_pause_white_24dp,
                    context.getString(R.string.label_pause),
                    MediaButtonReceiver.buildMediaButtonPendingIntent(context,
                            PlaybackStateCompat.ACTION_PAUSE))
                : new NotificationCompat.Action(R.drawable.ic_play_arrow_white_24dp,
                    context.getString(R.string.label_play),
                    MediaButtonReceiver.buildMediaButtonPendingIntent(context,
                            PlaybackStateCompat.ACTION_PLAY));

        MediaDescriptionCompat description = mMetadata.getDescription();
        Bitmap art = description.getIconBitmap();
        if (art == null) {
            // use a placeholder art while the remote art is being downloaded.
            art = BitmapFactory.decodeResource(context.getResources(),
                    R.drawable.ic_default_art);
        }

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(context);
        notificationBuilder
                .setStyle(new NotificationCompat.MediaStyle()
                        // show only play/pause in compact view.
                        .setShowActionsInCompactView(new int[]{0})
                        .setMediaSession(mediaSession.getSessionToken()))
                .addAction(action)
                .setSmallIcon(R.drawable.ic_notification)
                .setShowWhen(false)
                .setContentIntent(controller.getSessionActivity())
                .setContentTitle(description.getTitle())
                .setContentText(description.getSubtitle())
                .setLargeIcon(art)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

        return notificationBuilder.build();
    }
}
