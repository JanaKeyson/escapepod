/*
 * DownloadHelper.kt
 * Implements the DownloadHelper object
 * A DownloadHelper provides helper methods for downloading files
 *
 * This file is part of
 * ESCAPEPODS - Free and Open Podcast App
 *
 * Copyright (c) 2018 - Y20K.org
 * Licensed under the MIT-License
 * http://opensource.org/licenses/MIT
 */


package org.y20k.escapepods.helpers

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.preference.PreferenceManager
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.y20k.escapepods.core.Collection
import org.y20k.escapepods.core.Podcast
import org.y20k.escapepods.xml.RssHelper
import java.util.*


/*
 * DownloadHelper object
 */
object DownloadHelper {

    /* Define log tag */
    private val TAG: String = LogHelper.makeLogTag(DownloadHelper::class.java)


    /* Main class variables */
    private lateinit var collection: Collection
    private lateinit var downloadManager: DownloadManager
    private lateinit var activeDownloads: ArrayList<Long>


    /* Download a podcast */
    fun downloadPodcasts(context: Context, podcastUrlStrings: Array<String>) {
        // initialize main class variables, if necessary
        initialize(context)
        // convert array
        val uris: Array<Uri> =  Array<Uri>(podcastUrlStrings.size) { index -> Uri.parse(podcastUrlStrings[index])}
        // enqueue podcast
        enqueueDownload(context, uris, Keys.FILE_TYPE_RSS)
    }


    /* Download an episode */
    fun downloadEpisode(context: Context, podcastName: String, remoteImageFileLocation: String, ignoreWifiRestriction: Boolean) {
        // initialize main class variables, if necessary
        initialize(context)
        // enqueue episode
        val uris = Array(1) { remoteImageFileLocation.toUri() }
        enqueueDownload(context, uris, Keys.FILE_TYPE_AUDIO, podcastName, ignoreWifiRestriction)
    }


    /* Refresh cover of given podcast */
    fun refreshCover(context: Context, podcast: Podcast) {
        // initialize main class variables, if necessary
        initialize(context)
        // start to download podcast cover
        CollectionHelper.clearImagesFolder(context, podcast)
        val uris: Array<Uri>  = Array(1) {podcast.remoteImageFileLocation.toUri()}
        enqueueDownload(context, uris, Keys.FILE_TYPE_IMAGE, podcast.name)
    }


    /* Updates podcast collection */
    fun updateCollection(context: Context) {
        // initialize main class variables, if necessary
        initialize(context)
        // re-download all podcast xml episode lists
        if (CollectionHelper.hasEnoughTimePassedSinceLastUpdate(context)) {
            val uris: Array<Uri> = Array(collection.podcasts.size) { it ->
                collection.podcasts[it].remotePodcastFeedLocation.toUri()
            }
            enqueueDownload(context, uris, Keys.FILE_TYPE_RSS)
        } else {
            LogHelper.v(TAG, "Update not initiated: not enough time has passed since last update.")
        }
    }


    /* Processes a given download ID */
    fun processDownload(context: Context, downloadID: Long) {
        // initialize main class variables, if necessary
        initialize(context)
        // get local Uri in content://downloads/all_downloads/ for startDownload ID
        val localFileUri: Uri = downloadManager.getUriForDownloadedFile(downloadID)
        // get remote URL for startDownload ID
        val remoteFileLocation: String = getRemoteFileLocation(downloadManager, downloadID)
        // determine what to
        val fileType = FileHelper.getFileType(context, localFileUri)
        // Log completed startDownload // todo remove
        LogHelper.v(TAG, "Download complete: ${FileHelper.getFileName(context, localFileUri)} | ${FileHelper.getReadableByteCount(FileHelper.getFileSize(context, localFileUri), true)} | $fileType") // todo remove
        if (fileType in Keys.MIME_TYPES_RSS) readPodcastFeed(context, localFileUri, remoteFileLocation)
        if (fileType in Keys.MIME_TYPES_ATOM) LogHelper.w(TAG, "ATOM Feeds are not yet supported")
        if (fileType in Keys.MIME_TYPES_AUDIO) setEpisodeMediaUri(context, localFileUri, remoteFileLocation)
        if (fileType in Keys.MIME_TYPES_IMAGE) setPodcastImage(context, localFileUri, remoteFileLocation)
        // remove ID from active downloads
        removeFromActiveDownloads(context, downloadID)
    }


    /* Initializes main class variables of DownloadHelper, if necessary */
    private fun initialize(context: Context) {
        if (!this::collection.isInitialized || CollectionHelper.isNewerCollectionAvailable(context, collection.lastUpdate)) {
            LogHelper.v("Initializing / updating collection in DownloadHelper.") // todo remove
            collection = FileHelper.readCollection(context) // todo make async
        }
        if (!this::downloadManager.isInitialized) {
            downloadManager = context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as DownloadManager
        }
        if (!this::activeDownloads.isInitialized) {
            activeDownloads = loadActiveDownloads(context, downloadManager)
        }
    }


    /* Enqueues an Array of files in DownloadManager */
    private fun enqueueDownload(context: Context, uris: Array<Uri>, type: Int, podcastName: String = String(), ignoreWifiRestriction: Boolean = false) {
        // determine destination folder and allowed network types
        val folder: String = FileHelper.determineDestinationFolderPath(type, podcastName)
        val allowedNetworkTypes: Int = determineAllowedNetworkTypes(context, type, ignoreWifiRestriction)
        // enqueues downloads
        val newIDs = LongArray(uris.size)
        for (i in uris.indices)  {
            LogHelper.v(TAG, "DownloadManager enqueue: ${uris[i]}")
            if (uris[i].scheme.startsWith("http")) {
                val request: DownloadManager.Request = DownloadManager.Request(uris[i])
                        .setAllowedNetworkTypes(allowedNetworkTypes)
                        .setAllowedOverRoaming(false)
                        .setTitle(uris[i].lastPathSegment)
                        .setDestinationInExternalFilesDir(context, folder, uris[i].lastPathSegment)
                newIDs[i] = downloadManager.enqueue(request)
                activeDownloads.add(newIDs[i])
            }
        }
        saveActiveDownloads(context, activeDownloads)
    }


    /*  episode and podcast cover */
    private fun enqueuePodcastMediaFiles(context: Context, podcast: Podcast, isNew: Boolean) {
        if (isNew) {
            // start to download podcast cover
            CollectionHelper.clearImagesFolder(context, podcast)
            val coverUris: Array<Uri>  = Array(1) {podcast.remoteImageFileLocation.toUri()}
            enqueueDownload(context, coverUris, Keys.FILE_TYPE_IMAGE, podcast.name)
        }
        // start to download latest episode audio file
        val episodeUris: Array<Uri> = Array(1) {podcast.episodes[0].remoteAudioFileLocation.toUri()}
        enqueueDownload(context, episodeUris, Keys.FILE_TYPE_AUDIO, podcast.name)
    }


    /* Adds podcast to podcast collection*/
    private fun addPodcast(context: Context, podcast: Podcast, isNew: Boolean) {
        when (isNew) {
//            true -> collection = CollectionHelper.addPodcast(context, collection, podcast)
            true -> collection.podcasts.add(podcast)
            false -> collection = CollectionHelper.replacePodcast(context, collection, podcast)
        }
        // sort collection
        collection.podcasts.sortBy { it.name }
        // save collection
        saveCollection(context, true)
        // enqueue media files for download
        enqueuePodcastMediaFiles(context, podcast, isNew)
    }


    /* Sets podcast cover */
    private fun setPodcastImage(context: Context, localFileUri: Uri, remoteFileLocation: String) {
        collection.podcasts.forEach { podcast ->
            if (podcast.remoteImageFileLocation == remoteFileLocation) {
                podcast.cover = localFileUri.toString()
                podcast.episodes.forEach { episode ->
                    episode.cover = localFileUri.toString()
                }
            }
        }
        // save collection
        saveCollection(context)
    }


    /* Sets Media Uri in Episode */
    private fun setEpisodeMediaUri(context: Context, localFileUri: Uri, remoteFileLocation: String) {
        collection.podcasts.forEach { podcast ->
            podcast.episodes.forEach { episode ->
                if (episode.remoteAudioFileLocation == remoteFileLocation) {
                    episode.audio = localFileUri.toString()
                }
            }
        }
        // remove unused audio references from collection
        collection = CollectionHelper.removeUnusedAudioReferences(context, collection)
        // clear audio folder
        CollectionHelper.clearAudioFolder(context, collection)
        // save collection
        saveCollection(context)
    }


    /* Savely remove given startDownload ID from active downloads */
    private fun removeFromActiveDownloads(context: Context, downloadID: Long): Boolean {
        val iterator: MutableIterator<Long> = activeDownloads.iterator()
        while (iterator.hasNext()) {
            val activeDownload = iterator.next()
            if (activeDownload.equals(downloadID)) {
                iterator.remove()
                saveActiveDownloads(context, activeDownloads)
                return true
            }
        }
        return false
    }


    /* Saves podcast collection to storage */
    private fun saveCollection(context: Context, opmlExport: Boolean = false) {
        // set last update
        collection.lastUpdate = Calendar.getInstance().time
        // save collection
        CollectionHelper.saveCollection(context, collection, collection.lastUpdate)
        // export as OPML, if requested
        if (opmlExport) {CollectionHelper.exportCollection(context, collection)}
    }


    /* Async via coroutine: Reads podcast feed */
    private fun readPodcastFeed(context: Context, localFileUri: Uri, remoteFileLocation: String) {
        GlobalScope.launch() {
            LogHelper.v(TAG, "Reading podcast RSS file ($remoteFileLocation) - Thread: ${Thread.currentThread().name}")
            // async: readSuspended xml
            val deferred: Deferred<Podcast> = async { RssHelper().readSuspended(context, localFileUri, remoteFileLocation) }
            // wait for result and create podcast
            var podcast: Podcast = deferred.await()
            podcast = CollectionHelper.trimEpisodeList(context, podcast)
            podcast = CollectionHelper.fillEmptyEpisodeCovers(podcast)
            if (CollectionHelper.validatePodcast(podcast)) {
                // check if new
                val isNew: Boolean = CollectionHelper.isNewPodcast(podcast.remotePodcastFeedLocation, collection)
                // check if media download is necessary
                if (isNew || CollectionHelper.podcastHasDownloadableEpisodes(collection, podcast)) {
                    addPodcast(context, podcast, isNew)
                } else {
                    LogHelper.v(TAG, "No new media files to download.")
                }
            }
        }
    }


    /* Saves active downloads (IntArray) to shared preferences */
    private fun saveActiveDownloads(context: Context, activeDownloads: ArrayList<Long>) {
        val builder = StringBuilder()
        for (i in activeDownloads.indices) {
            builder.append(activeDownloads[i]).append(",")
        }
        PreferenceManager.getDefaultSharedPreferences(context).edit {putString(Keys.PREF_ACTIVE_DOWNLOADS, builder.toString())}
    }


    /* Loads active downloads (IntArray) from shared preferences */
    private fun loadActiveDownloads(context: Context, downloadManager: DownloadManager): ArrayList<Long> {
        val activeDownloadsString: String = PreferenceManager.getDefaultSharedPreferences(context).getString(Keys.PREF_ACTIVE_DOWNLOADS, "")
        val count = activeDownloadsString.split(",").size - 1
        val tokenizer = StringTokenizer(activeDownloadsString, ",")
        val activeDownloads: ArrayList<Long> = arrayListOf<Long>()
        repeat(count) {
            val token = tokenizer.nextToken().toLong()
            if (isDownloadActive(downloadManager, token)) {
                activeDownloads.add(token) }
        }
        return activeDownloads
    }


    /* Determines the remote file location (the original URL) */
    private fun getRemoteFileLocation(downloadManager: DownloadManager, downloadID: Long): String {
        var remoteFileLocation: String = ""
        val cursor: Cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadID))
        if (cursor.count > 0) {
            cursor.moveToFirst()
            remoteFileLocation = cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_URI))
        }
        return remoteFileLocation
    }


    /* Checks if a given download ID represents a finished download */
    private fun isDownloadFinished(downloadManager: DownloadManager, downloadID: Long): Boolean {
        var downloadStatus: Int = -1
        val cursor: Cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadID))
        if (cursor.count > 0) {
            cursor.moveToFirst()
            downloadStatus = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
        }
        return (downloadStatus == DownloadManager.STATUS_SUCCESSFUL)
    }


    /* Checks if a given download ID represents a finished download */
    private fun isDownloadActive(downloadManager: DownloadManager, downloadID: Long): Boolean {
        var downloadStatus: Int = -1
        val cursor: Cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadID))
        if (cursor.count > 0) {
            cursor.moveToFirst()
            downloadStatus = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
        }
        return (downloadStatus == DownloadManager.STATUS_RUNNING)
    }


    /* Determine allowed network type */
    private fun determineAllowedNetworkTypes(context: Context, type: Int, ignoreWifiRestriction: Boolean): Int {
        val downloadOverMobile = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(Keys.PREF_DOWNLOAD_OVER_MOBILE, Keys.DEFAULT_DOWNLOAD_OVER_MOBILE);
        var allowedNetworkTypes: Int =  (DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
        when (type) {
            Keys.FILE_TYPE_AUDIO -> {
                if (!downloadOverMobile || !ignoreWifiRestriction) {
                    allowedNetworkTypes = DownloadManager.Request.NETWORK_WIFI
                }
            }
        }
        return allowedNetworkTypes
    }

}