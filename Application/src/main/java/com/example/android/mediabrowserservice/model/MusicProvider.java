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

package com.example.android.mediabrowserservice.model;

import android.os.AsyncTask;
import android.support.v4.media.MediaMetadataCompat;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.util.Collections;
import java.util.LinkedHashMap;

/**
 * Utility class to get a list of MusicTrack's based on a server-side JSON
 * configuration.
 *
 * In a real application this class may pull data from a remote server, as we do here,
 * or potentially use {@link android.provider.MediaStore} to locate media files located on
 * the device.
 */
public class MusicProvider {

    private static final String TAG = MusicProvider.class.getSimpleName();

    public static final String MEDIA_ID_ROOT = "__ROOT__";
    public static final String MEDIA_ID_EMPTY_ROOT = "__EMPTY__";

    private static final String CATALOG_URL =
            "https://storage.googleapis.com/automotive-media/music.json";

    private static final String JSON_MUSIC = "music";
    private static final String JSON_TITLE = "title";
    private static final String JSON_ALBUM = "album";
    private static final String JSON_ARTIST = "artist";
    private static final String JSON_GENRE = "genre";
    private static final String JSON_SOURCE = "source";
    private static final String JSON_IMAGE = "image";
    private static final String JSON_TRACK_NUMBER = "trackNumber";
    private static final String JSON_TOTAL_TRACK_COUNT = "totalTrackCount";
    private static final String JSON_DURATION = "duration";

    // Categorized caches for music track data:
    private final LinkedHashMap<String, MediaMetadataCompat> mMusicListById;

    private enum State {
        NON_INITIALIZED, INITIALIZING, INITIALIZED
    }

    private volatile State mCurrentState = State.NON_INITIALIZED;

    /**
     * Callback used by MusicService.
     */
    public interface Callback {
        void onMusicCatalogReady(boolean success);
    }

    public MusicProvider() {
        mMusicListById = new LinkedHashMap<>();
    }

    public Iterable<MediaMetadataCompat> getAllMusics() {
        if (mCurrentState != State.INITIALIZED || mMusicListById.isEmpty()) {
            return Collections.emptyList();
        }
        return mMusicListById.values();
    }

    /**
     * Return the MediaMetadata for the given musicID.
     *
     * @param musicId The unique music ID.
     */
    public MediaMetadataCompat getMusic(String musicId) {
        return mMusicListById.containsKey(musicId) ? mMusicListById.get(musicId) : null;
    }

    /**
     * Update the metadata associated with a musicId. If the musicId doesn't exist, the
     * update is dropped. (That is, it does not create a new mediaId.)
     * @param musicId The ID
     * @param metadata New Metadata to associate with it
     */
    public synchronized void updateMusic(String musicId, MediaMetadataCompat metadata) {
        MediaMetadataCompat track = mMusicListById.get(musicId);
        if (track != null) {
            mMusicListById.put(musicId, metadata);
        }
    }

    public boolean isInitialized() {
        return mCurrentState == State.INITIALIZED;
    }

    /**
     * Get the list of music tracks from a server and caches the track information
     * for future reference, keying tracks by musicId and grouping by genre.
     */
    public void retrieveMediaAsync(final Callback callback) {
        Log.d(TAG, "retrieveMediaAsync called");
        if (mCurrentState == State.INITIALIZED) {
            // Already initialized, so call back immediately.
            callback.onMusicCatalogReady(true);
            return;
        }

        // Asynchronously load the music catalog in a separate thread
        new AsyncTask<Void, Void, State>() {
            @Override
            protected State doInBackground(Void... params) {
                retrieveMedia();
                return mCurrentState;
            }

            @Override
            protected void onPostExecute(State current) {
                if (callback != null) {
                    callback.onMusicCatalogReady(current == State.INITIALIZED);
                }
            }
        }.execute();
    }

    private synchronized void retrieveMedia() {
        try {
            if (mCurrentState == State.NON_INITIALIZED) {
                mCurrentState = State.INITIALIZING;

                int slashPos = CATALOG_URL.lastIndexOf('/');
                String path = CATALOG_URL.substring(0, slashPos + 1);
                JSONObject jsonObj = fetchJSONFromUrl(CATALOG_URL);
                if (jsonObj == null) {
                    return;
                }
                JSONArray tracks = jsonObj.getJSONArray(JSON_MUSIC);
                if (tracks != null) {
                    for (int j = tracks.length() - 1; j >= 0; j--) {
                        MediaMetadataCompat item = buildFromJSON(tracks.getJSONObject(j), path);
                        String musicId = item.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID);
                        mMusicListById.put(musicId, item);
                    }
                }
                mCurrentState = State.INITIALIZED;
            }
        } catch (JSONException jsonException) {
            Log.e(TAG, "Could not retrieve music list", jsonException);
        } finally {
            if (mCurrentState != State.INITIALIZED) {
                // Something bad happened, so we reset state to NON_INITIALIZED to allow
                // retries (eg if the network connection is temporary unavailable)
                mCurrentState = State.NON_INITIALIZED;
            }
        }
    }

    private MediaMetadataCompat buildFromJSON(JSONObject json, String basePath)
            throws JSONException {

        String title = json.getString(JSON_TITLE);
        String album = json.getString(JSON_ALBUM);
        String artist = json.getString(JSON_ARTIST);
        String genre = json.getString(JSON_GENRE);
        String source = json.getString(JSON_SOURCE);
        String iconUrl = json.getString(JSON_IMAGE);
        int trackNumber = json.getInt(JSON_TRACK_NUMBER);
        int totalTrackCount = json.getInt(JSON_TOTAL_TRACK_COUNT);
        int duration = json.getInt(JSON_DURATION) * 1000; // ms

        Log.d(TAG, "Found music track: " + json);

        // Media is stored relative to JSON file
        if (!source.startsWith("https")) {
            source = basePath + source;
        }
        if (!iconUrl.startsWith("https")) {
            iconUrl = basePath + iconUrl;
        }
        // Since we don't have a unique ID in the server, we fake one using the hashcode of
        // the music source. In a real world app, this could come from the server.
        String id = String.valueOf(source.hashCode());

        // Adding the music source to the MediaMetadata (and consequently using it in the
        // mediaSession.setMetadata) is not a good idea for a real world music app, because
        // the session metadata can be accessed by notification listeners. This is done in this
        // sample for convenience only.
        return new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, id)
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI, source)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                .putString(MediaMetadataCompat.METADATA_KEY_GENRE, genre)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, iconUrl)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, trackNumber)
                .putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, totalTrackCount)
                .build();
    }

    /**
     * Download a JSON file from a server, parse the content and return the JSON
     * object.
     *
     * @return result JSONObject containing the parsed representation.
     */
    private JSONObject fetchJSONFromUrl(String urlString) {
        InputStream inputStream = null;
        try {
            URL url = new URL(urlString);
            URLConnection urlConnection = url.openConnection();
            inputStream = new BufferedInputStream(urlConnection.getInputStream());
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    urlConnection.getInputStream(), "iso-8859-1"));
            StringBuilder stringBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                stringBuilder.append(line);
            }
            return new JSONObject(stringBuilder.toString());
        } catch (IOException | JSONException exception) {
            Log.e(TAG, "Failed to parse the json for media list", exception);
            return null;
        } finally {
            // If the inputStream was opened, try to close it now.
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException ignored) {
                    // Ignore the exception since there's nothing left to do with the stream
                }
            }
        }
    }
}
