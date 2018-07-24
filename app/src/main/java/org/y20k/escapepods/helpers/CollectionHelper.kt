/*
 * CollectionHelper.kt
 * Implements the CollectionHelper class
 * A CollectionHelper provides helper methods for the podcast collection
 *
 * This file is part of
 * ESCAPEPODS - Free and Open Podcast App
 *
 * Copyright (c) 2018 - Y20K.org
 * Licensed under the MIT-License
 * http://opensource.org/licenses/MIT
 */


package org.y20k.escapepods.helpers

import android.content.Context
import android.preference.PreferenceManager
import android.support.v4.media.MediaMetadataCompat
import org.y20k.escapepods.core.Collection
import org.y20k.escapepods.core.Episode
import org.y20k.escapepods.core.Podcast
import java.io.File
import java.util.*


/*
 * CollectionHelper class
 */
class CollectionHelper {

    /* Define log tag */
    private val TAG: String = LogHelper.makeLogTag(CollectionHelper::class.java)


    /* Creates a MediaMetadata for given Episode */
    fun createMediaMetadata(episode: Episode, podcast: Podcast): MediaMetadataCompat {
        return MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, episode.remoteAudioFileLocation.hashCode().toString())
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI, episode.audio)
                .putString(MediaMetadataCompat.METADATA_KEY_GENRE, "Podcast")
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, episode.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, podcast.name)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, podcast.name)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI,  podcast.cover)
//                .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, trackNumber)
//                .putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, totalTrackCount)
                .build();
    }


    /* Checks if feed is already in collection */
    fun isNewPodcast(remotePodcastFeedLocation: String, collection: Collection): Boolean {
        for (podcast in collection.podcasts) {
            if (podcast.remotePodcastFeedLocation == remotePodcastFeedLocation) return false
        }
        return true
    }


    /* Checks if enough time passed since last update */
    fun hasEnoughTimePassedSinceLastUpdate(collection: Collection): Boolean {
        val currentDate: Date = Calendar.getInstance().time
        return true // todo remove
//        return currentDate.time - collection.lastUpdate.time  > FIVE_MINUTES_IN_MILLISECONDS
    }


    /* Checks if podcast has episodes that can be downloaded */
    fun podcastHasDownloadableEpisodes(collection: Collection, newPodcast: Podcast): Boolean {
        // Step 1: New episode check -> compare GUIDs
        val oldPodcast = getPodcastFromCollection(collection, newPodcast)
        val newPodcastLatestEpisode: String = newPodcast.episodes[0].guid
        val oldPodcastLatestEpisode: String = oldPodcast.episodes[0].guid
        if (newPodcastLatestEpisode != oldPodcastLatestEpisode) {
            return true
        }
        // Step 2: Not yet downloaded episode check -> test if audio field is empty
        if (oldPodcast.episodes[0].audio.isEmpty()) {
            return true
        }
        // Default - no result in step 1 or 2
        return false
    }


    /* Clears an audio folder for a given podcast */
    fun clearAudioFolder(context: Context, collection: Collection, newPodcast: Podcast): Collection {
        // determine number of episodes to keep
        var numberOfEpisodesToKeep: Int = PreferenceManager.getDefaultSharedPreferences(context).getInt(Keys.PREF_NUMBER_OF_EPISODES_TO_DOWNLOAD, Keys.DEFAULT_DOWNLOAD_NUMBER_OF_EPISODES_TO_DOWNLOAD);
        if (newPodcast.episodes.size < numberOfEpisodesToKeep) {
            numberOfEpisodesToKeep = newPodcast.episodes.size
        }
        // clear audio folder
        val audioFolder: File = File(FileHelper().determineDestinationFolder(Keys.FILE_TYPE_AUDIO, newPodcast.name))
        FileHelper().clearFolder(audioFolder, numberOfEpisodesToKeep)

        // wipe audio file reference
        for (podcast: Podcast in collection.podcasts) {
            if (newPodcast.remotePodcastFeedLocation == podcast.remotePodcastFeedLocation) {
                for (episodeIndex: Int in getPodcastFromCollection(collection, podcast).episodes.indices) {
                    if (episodeIndex > numberOfEpisodesToKeep) {
                        podcast.episodes[episodeIndex].audio = ""
                    }
                }
            }
        }
        return collection
    }


    /* Clears an image folder for a given podcast */
    fun clearImageFolder(context: Context, podcast: Podcast) {
        // clear image folder
        val imagesFolder: File = File(context.getExternalFilesDir(Keys.FOLDER_IMAGES), FileHelper().determineDestinationFolder(Keys.FILE_TYPE_IMAGE, podcast.name))
        FileHelper().clearFolder(imagesFolder, 0)
    }


    /* Get the ID from collection for given podcast */
    fun getPodcastIdFromCollection(collection: Collection, podcast: Podcast): Int {
        collection.podcasts.indices.forEach {
            if (podcast.remotePodcastFeedLocation == collection.podcasts[it].remotePodcastFeedLocation) return it
        }
        return 0
    }


    /* Get the counterpart from collection for given podcast */
    private fun getPodcastFromCollection(collection: Collection, podcast: Podcast): Podcast {
        collection.podcasts.forEach {
            if (podcast.remotePodcastFeedLocation == it.remotePodcastFeedLocation) return it
        }
        return Podcast()
    }

}