/*
 * Copyright (C) 2012-2016 The Android Money Manager Ex Project Team
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.money.manager.ex.sync;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Messenger;
import android.text.TextUtils;
import android.widget.Toast;

import com.cloudrail.si.types.CloudMetaData;
import com.money.manager.ex.MoneyManagerApplication;
import com.money.manager.ex.R;
import com.money.manager.ex.core.IntentFactory;
import com.money.manager.ex.core.UIHelper;
import com.money.manager.ex.home.MainActivity;
import com.money.manager.ex.settings.SyncPreferences;
import com.money.manager.ex.utils.MmxDatabaseUtils;
import com.money.manager.ex.utils.NetworkUtils;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.joda.time.DateTime;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.Callable;

import rx.Single;
import rx.functions.Action1;
import timber.log.Timber;

/**
 * Class used to manage the database file synchronization process.
 */
public class SyncManager {

    public SyncManager(Context context) {
        mContext = context;
        mStorageClient = new CloudStorageClient(context);
    }

    private Context mContext;
    CloudStorageClient mStorageClient;
    private String mRemoteFile;
    private SyncPreferences mPreferences;
    // Used to temporarily disable auto-upload while performing batch updates.
    private boolean mAutoUploadDisabled = false;

    public void abortScheduledUpload() {
        Timber.d("Aborting scheduled download");

        PendingIntent pendingIntent = getPendingIntentForDelayedUpload();
        getAlarmManager().cancel(pendingIntent);
    }

    public Context getContext() {
        return mContext;
    }

    /**
     * Performs checks if automatic synchronization should be performed.
     * Used also on immediate upload after file changed.
     * @return boolean indicating if auto sync should be done.
     */
    public boolean canSync() {
        // check if enabled.
        if (!isActive()) return false;

        // should we sync only on wifi?
        if (getPreferences().shouldSyncOnlyOnWifi()) {
            Timber.d("Preferences set to sync on WiFi only.");

            // check if we are on WiFi connection.
            NetworkUtils network = new NetworkUtils(getContext());
            if (!network.isOnWiFi()) {
                Timber.i("Not on WiFi connection. Not synchronizing.");
                return false;
            }
        }

        return true;
    }

    public boolean isRemoteFileModified(CloudMetaData remoteFile) {
        DateTime cachedLastModified = getCachedLastModifiedDateFor(remoteFile);
        DateTime remoteLastModified = getModificationDate(remoteFile);

        return !remoteLastModified.isEqual(cachedLastModified);
    }

    public void disableAutoUpload() {
        mAutoUploadDisabled = true;
    }

    public Single<Void> downloadSingle(final CloudMetaData remoteFile, final File localFile) {
        return Single.fromCallable(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                downloadFile(remoteFile, localFile);
                return null;
            }
        })
        .doOnSuccess(new Action1<Void>() {
            @Override
            public void call(Void aVoid) {
                // clear local changes
                resetLocalChanges();

                // save any renewed tokens
                mStorageClient.cacheCredentials();

                cacheRemoteLastModifiedDate(remoteFile);

                abortScheduledUpload();
            }
        });
    }

    /**
     * Called whenever the database has changed and should be uploaded.
     * (Re-)Sets the timer for delayed sync of the database.
     */
    public void dataChanged() {
        if (!isSyncEnabled()) return;

        // Check if the current database is linked to a cloud service.
        String remotePath = getRemotePath();
        if (StringUtils.isEmpty(remotePath)) return;

        new SyncPreferences(getContext()).setLocalFileChanged(true);

        // Should we upload automatically?
        if (mAutoUploadDisabled) return;
        if (!canSync()) {
            Timber.i("Not on WiFi connection. Not synchronizing.");
            return;
        }

        // Should we schedule an upload?
        SyncPreferences preferences = new SyncPreferences(getContext());
        if (preferences.getUploadImmediately()) {
//            abortScheduledUpload();
//            scheduleUpload();
            scheduleDelayedUpload();
        }
    }

    public void enableAutoUpload() {
        mAutoUploadDisabled = false;
    }

    /**
     * Assembles the path where the local synchronised file is expected to be found.
     * @return The path of the local cached copy of the remote database.
     */
    public String getLocalPath() {
        String remoteFile = getRemotePath();
        // now get only the file name
        String remoteFileName = new File(remoteFile).getName();

        String localPath = getExternalStorageDirectoryForSync().getPath();
        if (!localPath.endsWith(File.separator)) {
            localPath += File.separator;
        }
        return localPath + remoteFileName;
    }

    public Single<List<CloudMetaData>> getRemoteFolderContentsSingle(String folder) {
        return mStorageClient.getContents(folder);
    }

    /**
     * Gets last saved datetime of the remote file modification from the preferences.
     * @param file file name, key
     * @return date of last modification
     */
    public DateTime getCachedLastModifiedDateFor(CloudMetaData file) {
        String dateString = getPreferences().get(file.getPath(), null);
        if (TextUtils.isEmpty(dateString)) return null;

        return new DateTime(dateString);
    }

    public DateTime getModificationDate(CloudMetaData remoteFile) {
        return new DateTime(remoteFile.getModifiedAt());
    }

    public String getRemotePath() {
        if (StringUtils.isEmpty(mRemoteFile)) {
            mRemoteFile = getPreferences().loadPreference(R.string.pref_remote_file, "");
        }
        return mRemoteFile;
    }

    public void invokeSyncService(String action) {
        // Validation.
        String remoteFile = getRemotePath();
        // We need a value in remote file name settings.
        if (TextUtils.isEmpty(remoteFile)) return;

        // Action

        ProgressDialog progressDialog = null;
        // Create progress dialog only if called from the UI.
        if ((getContext() instanceof Activity)) {
            try {
                //progress binaryDialog shown only when downloading an updated db file.
                progressDialog = new ProgressDialog(getContext());
                progressDialog.setCancelable(false);
                progressDialog.setMessage(getContext().getString(R.string.syncProgress));
                progressDialog.setIndeterminate(true);
//                progressDialog.show();
            } catch (Exception ex) {
                Timber.e(ex, "displaying sync progress binaryDialog");
            }
        }
        Messenger messenger = null;
        if (getContext() instanceof Activity) {
            // Messenger handles received messages from the sync service. Can run only in a looper thread.
            messenger = new Messenger(new SyncServiceMessageHandler(getContext(), progressDialog, remoteFile));
        }

        String localFile = getLocalPath();

        Intent syncServiceIntent = IntentFactory.getSyncServiceIntent(getContext(), action, localFile,
                remoteFile, messenger);
        // start service
        getContext().startService(syncServiceIntent);

        // Reset any other scheduled uploads as the current operation will modify the files.
        abortScheduledUpload();

        // The messages from the service are received via messenger.
    }

    /**
     * Indicates whether synchronization can be performed, meaning all of the criteria must be
     * true: sync enabled, respect wi-fi sync setting, provider is selected, network is online,
     * remote file is set.
     * @return A boolean indicating that sync can be performed.
     */
    public boolean isActive() {
        if (!isSyncEnabled()) return false;

        // network is online.
        NetworkUtils networkUtils = new NetworkUtils(getContext());
        if (!networkUtils.isOnline()) return false;

        // wifi preferences
        if (getPreferences().shouldSyncOnlyOnWifi()) {
            if (!networkUtils.isOnWiFi()) return false;
        }

        // Remote file must be set.
        if (StringUtils.isEmpty(getRemotePath())) {
            return false;
        }

        // check if a provider is selected? Default is Dropbox, so no need.

        return true;
    }

    boolean isSyncEnabled() {
        // The sync needs to be enabled.
        return getPreferences().isSyncEnabled();
    }

    /**
     * Retrieves the remote metadata. Retries once on fail to work around #957.
     * @return Remote file metadata.
     */
    public CloudMetaData loadMetadata(String remotePath) {
        return mStorageClient.loadMetadata(remotePath);
    }

    public Single<Void> login() {
        return mStorageClient.login();
    }

    public Single<Void> logout() {
        return mStorageClient.logout();
    }

    /**
     * Resets the synchronization preferences and cache.
     */
    void resetPreferences() {
        getPreferences().clear();

        // reset provider cache
        mStorageClient.createProviders();
        mStorageClient.cacheCredentials();
    }

    public void setEnabled(boolean enabled) {
        getPreferences().setSyncEnabled(enabled);
    }

    public void setProvider(CloudStorageProviderEnum provider) {
        mStorageClient.setProvider(provider);
    }

    public void setRemotePath(String value) {
        mRemoteFile = value;

        getPreferences().set(R.string.pref_remote_file, value);
    }

    public void setSyncInterval(int minutes) {
        getPreferences().setSyncInterval(minutes);
    }

    public void startSyncServiceAlarm() {
        Intent intent = new Intent(getContext(), SyncSchedulerBroadcastReceiver.class);
        intent.setAction(SyncSchedulerBroadcastReceiver.ACTION_START);
        getContext().sendBroadcast(intent);
        // SyncSchedulerBroadcastReceiver does not receive a brodcast when using LocalManager!
//        LocalBroadcastManager.getInstance(getContext()).sendBroadcast(intent);
    }

    public void stopSyncServiceAlarm() {
        Intent intent = new Intent(mContext, SyncSchedulerBroadcastReceiver.class);
        intent.setAction(SyncSchedulerBroadcastReceiver.ACTION_STOP);
        getContext().sendBroadcast(intent);
        // SyncSchedulerBroadcastReceiver does not receive a brodcast when using LocalManager!
//        LocalBroadcastManager.getInstance(getContext()).sendBroadcast(intent);
    }

    public void triggerSynchronization() {
        if (!isActive())  return;

        // Make sure that the current database is also the one linked in the cloud.
        String localPath = MoneyManagerApplication.getDatabasePath(getContext());
        if (TextUtils.isEmpty(localPath)) {
            new UIHelper(getContext()).showToast(R.string.filenames_differ);
            return;
        }

        String remotePath = getRemotePath();
        if (TextUtils.isEmpty(remotePath)) {
            Toast.makeText(getContext(), R.string.select_remote_file, Toast.LENGTH_SHORT).show();
            return;
        }

        // easy comparison, just by the file name.
        if (!areFileNamesSame(localPath, remotePath)) {
            // The current file was probably opened through Open Database.
            Toast.makeText(getContext(), R.string.db_not_dropbox, Toast.LENGTH_LONG).show();
            return;
        }

        invokeSyncService(SyncConstants.INTENT_ACTION_SYNC);
    }

    public void triggerDownload() {
        invokeSyncService(SyncConstants.INTENT_ACTION_DOWNLOAD);
    }

    public void triggerUpload() {
        String localFile = MoneyManagerApplication.getDatabasePath(getContext());
//        getLocalPath()
        String remoteFile = getRemotePath();

        // trigger upload
        Intent service = new Intent(getContext(), SyncService.class);
        service.setAction(SyncConstants.INTENT_ACTION_UPLOAD);
        service.putExtra(SyncConstants.INTENT_EXTRA_LOCAL_FILE, localFile);
        service.putExtra(SyncConstants.INTENT_EXTRA_REMOTE_FILE, remoteFile);

        // start service
        getContext().startService(service);
    }

    /**
     * Upload the file to cloud storage.
     * @param localPath The path to the file to upload.
     * @param remoteFile The remote path.
     */
    public boolean upload(String localPath, String remoteFile) {
        File localFile = new File(localPath);
        if (!localFile.exists()) return false;

        FileInputStream input;
        try {
            input = new FileInputStream(localFile);
        } catch (FileNotFoundException e) {
            Timber.e(e, "opening local file for upload");
            return false;
        }

        // Transfer the file.
        try {
            mStorageClient.upload(remoteFile, input, localFile.length(), true);
        } catch (Exception e) {
            Timber.e(e, "uploading database file");
            return false;
        }

        try {
            input.close();
        } catch (IOException e) {
            Timber.e(e, "closing input stream after upload");
        }

        // set last modified date
        CloudMetaData remoteFileMetadata = loadMetadata(remoteFile);
        if (remoteFileMetadata == null) {
            Timber.w("Could not retrieve metadata after upload! Aborting.");
            return false;
        }
        cacheRemoteLastModifiedDate(remoteFileMetadata);

        // Reset local changes indicator. todo this must handle changes made during the upload!
        resetLocalChanges();

        // set remote file, if not set (setLinkedRemoteFile)
        if (TextUtils.isEmpty(getRemotePath())) {
            setRemotePath(remoteFile);
        }

        // save any renewed tokens
        mStorageClient.cacheCredentials();

        return true;
    }

    /**
     * Sets the downloaded database as current. Restarts the Main Activity.
     */
    public void useDownloadedDatabase() {
        // Do this only if called from an activity.
        if (!(getContext() instanceof Activity)) return;

        MmxDatabaseUtils dbUtils = new MmxDatabaseUtils(getContext());
        boolean isDbSet = dbUtils.useDatabase(getLocalPath(), getRemotePath());

        if (!isDbSet) {
            Timber.w("could not change the database");
            return;
        }

        Intent intent = IntentFactory.getMainActivityNew(getContext());
        // Send info to not check for updates as it is redundant in this case.
        intent.putExtra(MainActivity.EXTRA_SKIP_REMOTE_CHECK, true);
        getContext().startActivity(intent);
    }

    /*
        Private
     */

    /**
     * Compares the local and remote db filenames. Use for safety check before synchronization.
     * @return A boolean indicating if the filenames are the same.
     */
    private boolean areFileNamesSame(String localPath, String remotePath) {
        if (StringUtils.isEmpty(localPath)) return false;
        if (StringUtils.isEmpty(remotePath)) return false;

        File localFile = new File(localPath);
        String localName = localFile.getName();

        File remoteFile = new File(remotePath);
        String remoteName = remoteFile.getName();

        return localName.equalsIgnoreCase(remoteName);
    }

    /**
     * Save the last modified datetime of the remote file into Settings for comparison during
     * the synchronization.
     * @param file file name
     */
    private void cacheRemoteLastModifiedDate(CloudMetaData file) {
        DateTime date = new DateTime(file.getModifiedAt());

        Timber.d("Saving last modification date %s for remote file %s", date.toString(), file);

        getPreferences().set(file.getPath(), date.toString());
    }

    /**
     * Downloads the file from the storage service.
     * @param remoteFile Remote file entry
     * @param localFile Local file reference
     * @return Indicator whether the download was successful.
     */
    private void downloadFile(CloudMetaData remoteFile, File localFile) throws IOException {
        InputStream inputStream = mStorageClient.download(remoteFile.getPath());
        OutputStream outputStream = new FileOutputStream(localFile, false);

        IOUtils.copy(inputStream, outputStream);

        inputStream.close();
        outputStream.close();
    }

    private File getExternalStorageDirectoryForSync() {
        // todo check this after refactoring the database utils.
        MmxDatabaseUtils dbUtils = new MmxDatabaseUtils(getContext());
        File folder = new File(dbUtils.getDefaultDatabaseDirectory());

        // manage folder
        if (folder.exists() && folder.isDirectory() && folder.canWrite()) {
            // create a folder for remote files
            File folderSync = new File(folder + "/sync");
            // check if folder exists otherwise create
            if (!folderSync.exists()) {
                if (!folderSync.mkdirs()) return getContext().getFilesDir();
            }
            return folderSync;
        } else {
            return mContext.getFilesDir();
        }
    }

    private SyncPreferences getPreferences() {
        if (mPreferences == null) {
            mPreferences = new SyncPreferences(getContext());
        }
        return mPreferences;
    }

    private void resetLocalChanges() {
        new SyncPreferences(getContext()).setLocalFileChanged(false);
    }

    /**
     * Schedule delayed upload via timer.
     */
    private void scheduleDelayedUpload() {
        PendingIntent pendingIntent = getPendingIntentForDelayedUpload();
        AlarmManager alarm = getAlarmManager();

        Timber.d("Setting delayed upload alarm.");

        // start the sync service after 30 seconds.
        alarm.set(AlarmManager.RTC_WAKEUP, DateTime.now().getMillis() + 30*1000, pendingIntent);
    }

    private PendingIntent getPendingIntentForDelayedUpload() {
        Intent intent = new Intent(getContext(), SyncService.class);

//        intent.setAction(SyncConstants.INTENT_ACTION_UPLOAD);
        intent.setAction(SyncConstants.INTENT_ACTION_SYNC);

        intent.putExtra(SyncConstants.INTENT_EXTRA_LOCAL_FILE, getLocalPath());
        intent.putExtra(SyncConstants.INTENT_EXTRA_REMOTE_FILE, getRemotePath());

        PendingIntent pintent = PendingIntent.getService(getContext(), SyncConstants.REQUEST_DELAYED_SYNC,
                intent, PendingIntent.FLAG_CANCEL_CURRENT);

        return pintent;
    }

    private AlarmManager getAlarmManager() {
        return (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
    }
}
