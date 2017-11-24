package link.standen.michael.phonesaver.activity

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import link.standen.michael.phonesaver.R
import link.standen.michael.phonesaver.util.LocationHelper
import android.provider.OpenableColumns
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.*
import java.io.*
import java.net.MalformedURLException
import java.net.URL

/**
 * An activity to handle saving files.
 * https://developer.android.com/training/sharing/receive.html
 */
class SaverActivity : ListActivity() {

	private val TAG = "SaverActivity"

	private val FILENAME_REGEX = "[^-_.A-Za-z0-9]"
	private val FILENAME_LENIENT_REGEX = "[\\p{Cntrl}]"
	private val FILENAME_LENGTH_LIMIT = 100

	private val FILENAME_EXT_MATCH_LIMIT = 1000

	private var FORCE_SAVING = false
	private var REGISTER_MEDIA_SCANNER = false
	private var USE_LENIENT_REGEX = false

	private var location: String? = null

	data class Pair(val key: String, val value: String)
	private var debugInfo: MutableList<Pair> = mutableListOf()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.saver_activity)

		val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
		FORCE_SAVING = sharedPrefs.getBoolean("force_saving", false)
		REGISTER_MEDIA_SCANNER = sharedPrefs.getBoolean("register_file", false)
		USE_LENIENT_REGEX = sharedPrefs.getBoolean("lenient_regex", false)

		when {
			FORCE_SAVING -> loadList()
			else -> {
				useIntent({ success ->
					Log.i(TAG, "Supported: $success")
					// Success should never be null on a dryRun
					if (success!!){
						loadList()
					} else {
						showNotSupported()
					}
				}, dryRun=true)
			}
		}
	}

	private fun loadList() {
		LocationHelper.loadFolderList(this)?.let {
			when {
				it.size > 1 -> {
					runOnUiThread {
						findViewById<View>(R.id.loading).visibility = View.GONE
						// Init list view
						val listView = findViewById<ListView>(android.R.id.list)
						listView.onItemClickListener = AdapterView.OnItemClickListener { _, view, _, _ ->
							location = LocationHelper.addRoot((view as TextView).text.toString())
							useIntent({ finishIntent(it) })
						}
						listView.adapter = ArrayAdapter<String>(this, R.layout.saver_list_item, it.map { if (it.isBlank()) File.separator else it })
					}
					return // await selection
				}
				it.size == 1 -> {
					// Only one location, just use it
					location = LocationHelper.addRoot(it[0])
					useIntent({ finishIntent(it) })
					return // activity dead
				}
				else -> {
					runOnUiThread {
						Toast.makeText(this, R.string.toast_save_init_no_locations, Toast.LENGTH_LONG).show()
						exitApplication()
					}
					return // activity dead
				}
			}
		}

		runOnUiThread {
			Toast.makeText(this, R.string.toast_save_init_error, Toast.LENGTH_LONG).show()
			exitApplication()
		}
		return // activity dead
	}

	private fun useIntent(callback: (success: Boolean?) -> Unit, dryRun: Boolean = false) {
		// Get intent action and MIME type
		val action: String? = intent.action
		val type: String? = intent.type

		Log.i(TAG, "Action: $action")
		Log.i(TAG, "Type: $type")

		type?.toLowerCase()?.let {
			if (Intent.ACTION_SEND == action) {
				return handleSingle(callback, dryRun)
			} else if (Intent.ACTION_SEND_MULTIPLE == action) {
				return handleMultiple(callback, dryRun)
			}

			if (FORCE_SAVING) {
				// Save the file the best way we can
				return handleSingle(callback, dryRun)
			}
		}

		Log.i(TAG, "No supporting method")

		// Failed to reach callback
		finishIntent(false)
	}

	/**
	 * Show the not supported information.
	 */
	private fun showNotSupported() {
		// Hide list
		runOnUiThread {
			findViewById<View>(R.id.loading).visibility = View.GONE
			findViewById<View>(android.R.id.list).visibility = View.GONE
			// Generate issue text here as should always be English and does not need to be in strings.xml
			val bobTitle = StringBuilder()
			bobTitle.append("Support Request - ")
			bobTitle.append(intent.type)
			val bobBody = StringBuilder()
			bobBody.append("Support request. Generated by Phone Saver.%0D%0A")
			bobBody.append("%0D%0AIntent type: ")
			bobBody.append(intent.type)
			bobBody.append("%0D%0AIntent action: ")
			bobBody.append(intent.action)
			intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
				bobBody.append("%0D%0AText: ")
				bobBody.append(it)
			}
			intent.getStringExtra(Intent.EXTRA_SUBJECT)?.let {
				bobBody.append("%0D%0ASubject: ")
				bobBody.append(it)
			}
			debugInfo.forEach {
				bobBody.append("%0D%0A${it.key}: ")
				bobBody.append(it.value)
			}
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
				intent.getStringExtra(Intent.EXTRA_HTML_TEXT)?.let {
					bobBody.append("%0D%0AHTML Text: ")
					bobBody.append(it)
				}
			}
			// Version
			try {
				val versionName = packageManager.getPackageInfo(packageName, 0).versionName
				bobBody.append("%0D%0AApplication Version: ")
				bobBody.append(versionName)
			} catch (e: PackageManager.NameNotFoundException) {
				Log.e(TAG, "Unable to get package version", e)
			}
			bobBody.append("%0D%0A%0D%0AMore information: TYPE_ADDITIONAL_INFORMATION_HERE")
			bobBody.append("%0D%0A%0D%0AThank you")
			val issueLink = "https://github.com/ScreamingHawk/phone-saver/issues/new?title=" +
					bobTitle.toString().replace(" ", "%20") +
					"&body=" +
					bobBody.toString().replace(" ", "%20").replace("=", "%3D")
			Log.i(TAG, issueLink)

			// Build and show unsupported message
			val supportView = findViewById<TextView>(R.id.not_supported)
			@Suppress("DEPRECATION")
			supportView.text = Html.fromHtml(resources.getString(R.string.not_supported, issueLink))
			supportView.movementMethod = LinkMovementMethod.getInstance()
			findViewById<View>(R.id.not_supported_wrapper).visibility = View.VISIBLE
		}
	}

	/**
	 * Call when the intent is finished
	 */
	private fun finishIntent(success: Boolean?, messageId: Int? = null) {
		// Notify user
		runOnUiThread {
			when {
				messageId != null -> Toast.makeText(this, messageId, Toast.LENGTH_SHORT).show()
				success == null -> Toast.makeText(this, R.string.toast_save_in_progress, Toast.LENGTH_SHORT).show()
				success -> Toast.makeText(this, R.string.toast_save_successful, Toast.LENGTH_SHORT).show()
				else -> Toast.makeText(this, R.string.toast_save_failed, Toast.LENGTH_SHORT).show()
			}
		}

		exitApplication()
	}

	/**
	 * Exists the application is the best way available for the Android version
	 */
	@SuppressLint("NewApi")
	private fun exitApplication() {
		when {
			Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP -> finishAndRemoveTask()
			Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN -> finishAffinity()
			else -> finish()
		}
	}

	/**
	 * Handle the saving of single items.
	 */
	private fun handleSingle(callback: (success: Boolean?) -> Unit, dryRun: Boolean) {
		// Try save stream first
		intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let {
			Log.d(TAG, "Text has stream")
			getFilename(it, intent.type, dryRun, {filename ->
				saveUri(it, filename, callback, dryRun)
			})
			return
		}

		// Save the text
		intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
			Log.d(TAG, "Text Extra: $it")
			object: AsyncTask<Unit, Unit, Unit>(){
				override fun doInBackground(vararg params: Unit?) {
					try {
						val url = URL(it)
						// It's a URL
						Log.d(TAG, "Text with URL")
						val mime = MimeTypeMap.getSingleton()
						val urlContentType = mime.getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(it))
								// Fall back to checking URL content type
								?: url.openConnection().getHeaderField("Content-Type")
						urlContentType?.toLowerCase()?.let { contentType ->
							Log.d(TAG, "URL Content-Type: $contentType")
							debugInfo.add(Pair("URL Content-Type", contentType))
							getFilename(intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: Uri.parse(it).lastPathSegment,
									contentType, dryRun, { filename ->
										if (contentType.startsWith("image/") ||
												contentType.startsWith("video/") ||
												contentType.startsWith("audio/")) {
											saveUrl(Uri.parse(it), filename, callback, dryRun)
										} else if (contentType.startsWith("text/")){
											saveString(it, filename, callback, dryRun)
										} else if (FORCE_SAVING && !dryRun){
											// Fallback to saving with saveUrl
											saveUrl(Uri.parse(it), filename, callback, dryRun)
										} else {
											callback(false)
										}
							})
						}?: callback(false)
					} catch (e: MalformedURLException){
						Log.d(TAG, "Text without URL")
						// It's just some text
						val mimeType: String = intent.type?.toLowerCase() ?: "text/plain"
						getFilename(intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: it,
								mimeType, dryRun, { filename ->
							saveString(it, filename, callback, dryRun)
						})
					}
				}
			}.execute()
		} ?: callback(false)
	}

	/**
	 * Handle the saving of multiple streams.
	 */
	private fun handleMultiple(callback: (success: Boolean?) -> Unit, dryRun: Boolean) {
		val imageUris: ArrayList<Uri>? = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
		imageUris?.let {
			var counter = 0
			var completeSuccess = true
			imageUris.forEach {
				getFilename(it, intent.type, dryRun, { filename->
					saveUri(it, filename, { success ->
						counter++
						success?.let {
							completeSuccess = completeSuccess && it
						}
						if (counter == imageUris.size){
							callback(completeSuccess)
						}
					}, dryRun)
				})
			}
		} ?: callback(false)
	}

	/**
	 * Save the given uri to filesystem.
	 */
	private fun saveUri(uri: Uri, filename: String, callback: (success: Boolean?) -> Unit, dryRun: Boolean) {
		val destinationFilename = safeAddPath(filename)

		if (!dryRun) {
			val sourceFilename = uri.path
			Log.d(TAG, "Saving $sourceFilename to $destinationFilename")
		}

		try {
			contentResolver.openInputStream(uri)?.use { bis ->
				saveStream(bis, destinationFilename, callback, dryRun)
			} ?: callback(false)
		} catch (e: FileNotFoundException){
			Log.e(TAG, "File not found. Perhaps you are overriding the same file and just deleted it?", e)
			callback(false)
		}
	}

	/**
	 * Save the given url to the filesystem.
	 */
	fun saveUrl(uri: Uri, filename: String, callback: (success: Boolean?) -> Unit, dryRun: Boolean) {
		if (dryRun){
			// This entire method can be skipped when doing a dry run
			return callback(true)
		}

		var success: Boolean? = false

		location?.let {
			val sourceFilename = uri.toString()
			val destinationFilename = safeAddPath(filename)

			Log.d(TAG, "Saving $sourceFilename to $destinationFilename")

			val downloader = DownloadManager.Request(uri)
			downloader.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_MOBILE or DownloadManager.Request.NETWORK_WIFI)
					.setAllowedOverRoaming(true)
					.setTitle(filename)
					.setDescription(resources.getString(R.string.downloader_description, sourceFilename))
					.setDestinationInExternalPublicDir(LocationHelper.removeRoot(it), filename)

			if (REGISTER_MEDIA_SCANNER){
				downloader.allowScanningByMediaScanner()
			}

			(getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(downloader)

			success = null
		}

		callback(success)
	}

	/**
	 * Save a stream to the filesystem.
	 */
	private fun saveStream(bis: InputStream, destinationFilename: String,
						   callback: (success: Boolean?) -> Unit, dryRun: Boolean) {
		if (dryRun){
			// This entire method can be skipped when doing a dry run
			return callback(true)
		}

		var success = false
		var bos: BufferedOutputStream? = null

		try {
			val fout = File(destinationFilename)
			if (!fout.exists()){
				fout.createNewFile()
			}
			bos = BufferedOutputStream(FileOutputStream(fout, false))
			val buf = ByteArray(1024)
			var bytesRead = bis.read(buf)
			while (bytesRead != -1) {
				bos.write(buf, 0, bytesRead)
				bytesRead = bis.read(buf)
			}

			// Done
			success = true

			if (REGISTER_MEDIA_SCANNER){
				MediaScannerConnection.scanFile(this, arrayOf(destinationFilename), null, null)
			}
		} catch (e: IOException) {
			Log.e(TAG, "Unable to save file", e)
		} finally {
			try {
				bos?.close()
			} catch (e: IOException) {
				Log.e(TAG, "Unable to close stream", e)
			}
		}
		callback(success)
	}

	/**
	 * Save a string to the filesystem.
	 */
	private fun saveString(s: String, filename: String, callback: (success: Boolean?) -> Unit,
						   dryRun: Boolean) {
		if (dryRun){
			// This entire method can be skipped when doing a dry run
			return callback(true)
		}

		val destinationFilename = safeAddPath(filename)
		var success = false
		var bw: BufferedWriter? = null

		try {
			val fout = File(destinationFilename)
			if (!fout.exists()){
				fout.createNewFile()
			}
			bw = BufferedWriter(FileWriter(destinationFilename))
			bw.write(s)

			// Done
			success = true

			if (REGISTER_MEDIA_SCANNER){
				MediaScannerConnection.scanFile(this, arrayOf(destinationFilename), null, null)
			}
		} catch (e: IOException) {
			Log.e(TAG, "Unable to save file", e)
		} finally {
			try {
				bw?.close()
			} catch (e: IOException) {
				Log.e(TAG, "Unable to close stream", e)
			}
		}
		callback(success)
	}

	/**
	 * Get the filename from a Uri.
	 */
	private fun getFilename(uri: Uri, mime: String, dryRun: Boolean, callback: (filename: String) -> Unit) {
		// Find the actual filename
		if (uri.scheme == "content") {
			contentResolver.query(uri, null, null, null, null)?.use {
				if (it.moveToFirst()) {
					return getFilename(it.getString(it.getColumnIndex(OpenableColumns.DISPLAY_NAME)), mime, dryRun, callback)
				}
			}
		}
		getFilename(uri.lastPathSegment, mime, dryRun, callback)
	}

	/**
	 * Get the filename from a string.
	 */
	private fun getFilename(s: String, mime: String, dryRun: Boolean, callback: (filename: String) -> Unit) {
		// Validate the mime type
		Log.d(TAG, "Converting mime: $mime")
		val convertedMime = mime.replaceAfter(";", "").replace(";", "")
		Log.d(TAG, "Converted mime: $convertedMime")

		Log.d(TAG, "Converting filename: $s")

		var result = s
				// Take last section after a slash (excluding the slash)
				.replaceBeforeLast("/", "").replace("/", "")
				// Take first section before a space (excluding the space)
				.replaceAfter(" ", "").replace(" ", "")
				// Remove non-filename characters
				.replace(Regex(if (USE_LENIENT_REGEX) FILENAME_LENIENT_REGEX else FILENAME_REGEX), "")

		if (result.length > FILENAME_LENGTH_LIMIT) {
			// Do not go over the filename length limit
			result = result.substring(0, FILENAME_LENGTH_LIMIT)
		}

		var ext = result.substringAfterLast('.', "")
		if (!MimeTypeMap.getSingleton().hasExtension(ext)){
			// Add file extension
			MimeTypeMap.getSingleton().getExtensionFromMimeType(convertedMime)?.let {
				ext = it
				Log.d(TAG, "Adding extension $it to $result")
				result += "." + it
			}
		}

		Log.d(TAG, "Converted filename: $result")

		if (!dryRun) {
			val f = File(safeAddPath(result))
			if (f.exists()) {
				when (resources.getStringArray(R.array.pref_list_values_file_exists).indexOf(
						PreferenceManager.getDefaultSharedPreferences(this).getString(
								("file_exists"), resources.getString(R.string.pref_default_value_file_exists)))) {
					0 -> {
						// Overwrite. Delete the file, so that it will be overridden
						Log.d(TAG, "Overwriting $result")
						f.delete()
					}
					1 -> {
						// Nothing. Quit
						Log.d(TAG, "Quitting due to duplicate $result")
						finishIntent(false, R.string.toast_save_file_exists)
						return
					}
					2 -> {
						// Postfix. Add counter before extension
						Log.d(TAG, "Adding postfix to $result")
						var i = 1
						val before = safeAddPath(result.substringBeforeLast('.', "")) + "."
						if (ext.isNotBlank()) {
							ext = "." + ext
						}
						while (File(before + i + ext).exists()) {
							i++
							if (i > FILENAME_EXT_MATCH_LIMIT) {
								// We have a lot of matches. This is too hard
								Log.w(TAG, "There are over $FILENAME_EXT_MATCH_LIMIT matches for $before$ext. Aborting.")
								finishIntent(false, R.string.toast_save_file_exists)
								return
							}
						}
						result = before + i + ext
					}
					3 -> {
						// Request
						Log.e(TAG, "Not implemented!")
						throw NotImplementedError("Requesting filename not yet implemented.")
					}
				}
			}
		}

		callback(result)
	}

	/**
	 * Add the location path if not null and not already added.
	 */
	private fun safeAddPath(filename: String): String {
		location?.let {
			if (!filename.startsWith(it)){
				return it + File.separatorChar + filename
			}
		}
		return filename
	}
}
