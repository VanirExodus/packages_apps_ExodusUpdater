/*
 * Copyright (C) 2012 The Exodus Project (DvTonder)
 *
 * * Licensed under the GNU GPLv2 license
 *
 * The text of the license can be found in the LICENSE file
 * or at https://www.gnu.org/licenses/gpl-2.0.txt
 */

package com.exodus.updater;

import android.app.ActionBar;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.exodus.updater.misc.Constants;
import com.exodus.updater.misc.State;
import com.exodus.updater.misc.UpdateInfo;
import com.exodus.updater.receiver.DownloadReceiver;
import com.exodus.updater.service.UpdateCheckService;
import com.exodus.updater.utils.UpdateFilter;
import com.exodus.updater.utils.Utils;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedList;

public class UpdatesSettings extends PreferenceActivity implements
        OnPreferenceChangeListener, UpdatePreference.OnReadyListener, UpdatePreference.OnActionListener, PreferenceManager.OnPreferenceTreeClickListener {
    private static String TAG = "UpdatesSettings";

    // intent extras
    public static final String EXTRA_UPDATE_LIST_UPDATED = "update_list_updated";
    public static final String EXTRA_FINISHED_DOWNLOAD_ID = "download_id";
    public static final String EXTRA_FINISHED_DOWNLOAD_PATH = "download_path";

    private static final String LATEST_CATEGORY = "latest_category";
    private static final String UPDATES_CATEGORY = "updates_category";

    private static final int MENU_REFRESH = 0;
    private static final int MENU_DELETE_ALL = 1;
    private static final int MENU_SYSTEM_INFO = 2;
    private static final int MENU_GAPPS_LINK = 3;

    private static boolean isMLatestListRemoved = false;
    private SharedPreferences mPrefs;
    private CheckBoxPreference mBackupRom;
    private ListPreference mUpdateCheck;
    // private ListPreference mUpdateType;

    private PreferenceCategory mLatestList;
    private PreferenceCategory mUpdatesList;
    private UpdatePreference mDownloadingPreference;

    private File mUpdateFolder;

    private boolean mStartUpdateVisible = false;
    private ProgressDialog mProgressDialog;

    private DownloadManager mDownloadManager;
    private boolean mDownloading = false;
    private long mDownloadId;
    private String mFileName;
    private int selected = -1;

    private Handler mUpdateHandler = new Handler();

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (DownloadReceiver.ACTION_DOWNLOAD_STARTED.equals(action)) {
                mDownloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                mUpdateHandler.post(mUpdateProgress);
            } else if (UpdateCheckService.ACTION_CHECK_FINISHED.equals(action)) {
                if (mProgressDialog != null) {
                    mProgressDialog.dismiss();
                    mProgressDialog = null;

                    int count = intent.getIntExtra(UpdateCheckService.EXTRA_NEW_UPDATE_COUNT, -1);
                    if (count == 0) {
                        Toast.makeText(UpdatesSettings.this, R.string.no_updates_found,
                                Toast.LENGTH_SHORT).show();
                    } else if (count < 0) {
                        Toast.makeText(UpdatesSettings.this, R.string.update_check_failed,
                                Toast.LENGTH_LONG).show();
                    }
                }
                updateLayout();
            }
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mDownloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);

        // Load the layouts
        addPreferencesFromResource(R.xml.main);
        mLatestList = (PreferenceCategory) findPreference(LATEST_CATEGORY);
        mUpdatesList = (PreferenceCategory) findPreference(UPDATES_CATEGORY);
        mUpdateCheck = (ListPreference) findPreference(Constants.UPDATE_CHECK_PREF);
        // mUpdateType = (ListPreference) findPreference(Constants.UPDATE_TYPE_PREF);

        // Load the stored preference data
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (mUpdateCheck != null) {
            int check = mPrefs.getInt(Constants.UPDATE_CHECK_PREF, Constants.UPDATE_FREQ_WEEKLY);
            mUpdateCheck.setValue(String.valueOf(check));
            mUpdateCheck.setSummary(mapCheckValue(check));
            mUpdateCheck.setOnPreferenceChangeListener(this);
        }

        //mGapps = (Preference) findPreference("check_dho_gapps");

        /* We don't need this for the moment
        if (mUpdateType != null) {
            int type = mPrefs.getInt(Constants.UPDATE_TYPE_PREF, 0);
            mUpdateType.setValue(String.valueOf(type));
            mUpdateType.setSummary(mUpdateType.getEntries()[type]);
            mUpdateType.setOnPreferenceChangeListener(this);
        } */

        /* TODO: add this back once we have a way of doing backups that is not recovery specific
        mBackupRom = (CheckBoxPreference) findPreference(Constants.BACKUP_PREF);
        mBackupRom.setChecked(mPrefs.getBoolean(Constants.BACKUP_PREF, true));
        */

        // Set 'HomeAsUp' feature of the actionbar to fit better into Settings
        final ActionBar bar = getActionBar();
        try {
             bar.setDisplayHomeAsUpEnabled(true);
        } catch(java.lang.NullPointerException ex) {
             // YOLO
            Log.e(TAG,"Something went wrong :"+ex);
        }

        // Turn on the Options Menu
        invalidateOptionsMenu();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_REFRESH, 0, R.string.menu_refresh)
                .setIcon(R.drawable.ic_menu_refresh)
                .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS
                        | MenuItem.SHOW_AS_ACTION_WITH_TEXT);

        menu.add(0, MENU_DELETE_ALL, 0, R.string.menu_delete_all)
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);

        menu.add(0, MENU_SYSTEM_INFO, 0, R.string.menu_system_info)
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);

        menu.add(0, MENU_GAPPS_LINK , 0, R.string.gapps_menu_title)
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_REFRESH:
                checkForUpdates();
                return true;

            case MENU_DELETE_ALL:
                confirmDeleteAll();
                return true;

            case MENU_SYSTEM_INFO:
                showSysInfo();
                return true;

            case android.R.id.home:
                onBackPressed();
                return true;

            case MENU_GAPPS_LINK:
                showGappsLink();
                return true;
        }
        return false;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        // Check if we need to refresh the screen to show new updates
        if (intent.getBooleanExtra(EXTRA_UPDATE_LIST_UPDATED, false)) {
            updateLayout();
        }

        checkForDownloadCompleted(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // If running on a phone, remove padding around the listview
        if (!Utils.isTablet(this)) {
            getListView().setPadding(0, 0, 0, 0);
        }
    }

    @Override
    public void onReady(UpdatePreference pref) {
        pref.setOnReadyListener(null);
        mUpdateHandler.post(mUpdateProgress);
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        return super.onPreferenceTreeClick(preferenceScreen, preference);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mUpdateCheck) {
            int value = Integer.valueOf((String) newValue);
            mPrefs.edit().putInt(Constants.UPDATE_CHECK_PREF, value).apply();
            mUpdateCheck.setSummary(mapCheckValue(value));
            Utils.scheduleUpdateService(this, value * 1000);
            return true;
        /*} else if (preference == mUpdateType) {
            int value = Integer.valueOf((String) newValue);
            mPrefs.edit().putInt(Constants.UPDATE_TYPE_PREF, value).apply();
            mUpdateType.setSummary(mUpdateType.getEntries()[value]);
            checkForUpdates();
            return true; */
        }

        return false;
    }

    @Override
    protected void onStart() {
        super.onStart();

        // Determine if there are any in-progress downloads
        mDownloadId = mPrefs.getLong(Constants.DOWNLOAD_ID, -1);
        if (mDownloadId >= 0) {
            Cursor c = mDownloadManager.query(new DownloadManager.Query().setFilterById(mDownloadId));
            if (c == null || !c.moveToFirst()) {
                Toast.makeText(this, R.string.download_not_found, Toast.LENGTH_LONG).show();
            } else {
                String fileName = c.getString(c.getColumnIndex(DownloadManager.COLUMN_LOCAL_FILENAME));
                int status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
                if (fileName != null) {
                    if (status == DownloadManager.STATUS_PENDING
                            || status == DownloadManager.STATUS_RUNNING
                            || status == DownloadManager.STATUS_PAUSED) {
                        File file = new File(fileName);
                        mFileName = file.getName().replace(".partial", "");
                    }
                }
            }
            if (c != null) {
                c.close();
            }
        } else {
            resetDownloadState();
        }

        updateLayout();

        IntentFilter filter = new IntentFilter(UpdateCheckService.ACTION_CHECK_FINISHED);
        filter.addAction(DownloadReceiver.ACTION_DOWNLOAD_STARTED);
        registerReceiver(mReceiver, filter);

        checkForDownloadCompleted(getIntent());
        setIntent(null);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mUpdateHandler.removeCallbacks(mUpdateProgress);
        unregisterReceiver(mReceiver);
        if (mProgressDialog != null) {
            mProgressDialog.cancel();
            mProgressDialog = null;
        }
    }

    @Override
    public void onStartDownload(UpdatePreference pref) {
        // If there is no internet connection, display a message and return.
        if (!Utils.isOnline(this)) {
            Toast.makeText(this, R.string.data_connection_required, Toast.LENGTH_SHORT).show();
            return;
        }

        if (mDownloading) {
            Toast.makeText(this, R.string.download_already_running, Toast.LENGTH_LONG).show();
            return;
        }

        // We have a match, get ready to trigger the download
        mDownloadingPreference = pref;

        UpdateInfo ui = mDownloadingPreference.getUpdateInfo();
        if (ui == null) {
            return;
        }

        mDownloadingPreference.setStyle(UpdatePreference.STYLE_DOWNLOADING);
        mFileName = ui.getFileName();
        mDownloading = true;

        // Start the download
        Intent intent = new Intent(this, DownloadReceiver.class);
        intent.setAction(DownloadReceiver.ACTION_START_DOWNLOAD);
        intent.putExtra(DownloadReceiver.EXTRA_UPDATE_INFO, (Parcelable) ui);
        sendBroadcast(intent);

        mUpdateHandler.post(mUpdateProgress);
    }

    private Runnable mUpdateProgress = new Runnable() {
        public void run() {
            if (!mDownloading || mDownloadingPreference == null || mDownloadId < 0) {
                return;
            }

            ProgressBar progressBar = mDownloadingPreference.getProgressBar();
            if (progressBar == null) {
                return;
            }

            DownloadManager.Query q = new DownloadManager.Query();
            q.setFilterById(mDownloadId);

            Cursor cursor = mDownloadManager.query(q);
            int status;

            if (cursor == null || !cursor.moveToFirst()) {
                // DownloadReceiver has likely already removed the download
                // from the DB due to failure or MD5 mismatch
                status = DownloadManager.STATUS_FAILED;
            } else {
                status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));
            }

            switch (status) {
                case DownloadManager.STATUS_PENDING:
                    progressBar.setIndeterminate(true);
                    break;
                case DownloadManager.STATUS_PAUSED:
                case DownloadManager.STATUS_RUNNING:
                    int downloadedBytes = cursor.getInt(
                        cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    int totalBytes = cursor.getInt(
                        cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));

                    progressBar.setIndeterminate(totalBytes < 0);
                    progressBar.setMax(totalBytes);
                    progressBar.setProgress(downloadedBytes);
                    break;
                case DownloadManager.STATUS_FAILED:
                    if (mDownloadingPreference.getDependency() == LATEST_CATEGORY)
                        mDownloadingPreference.setStyle(UpdatePreference.STYLE_NEW);
                    else
                        mDownloadingPreference.setStyle(UpdatePreference.STYLE_OLD);
                    resetDownloadState();
                    break;
            }

            if (cursor != null) {
                cursor.close();
            }
            if (status != DownloadManager.STATUS_FAILED) {
                mUpdateHandler.postDelayed(this, 1000);
            }
        }
    };

    @Override
    public void onStopDownload(final UpdatePreference pref) {
        if (!mDownloading || mFileName == null || mDownloadId < 0) {
            if (pref.getDependency() == LATEST_CATEGORY)
                pref.setStyle(UpdatePreference.STYLE_NEW);
            else
                pref.setStyle(UpdatePreference.STYLE_OLD);
            resetDownloadState();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_download_cancelation_dialog_title)
                .setMessage(R.string.confirm_download_cancelation_dialog_message)
                .setPositiveButton(R.string.dialog_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // Set the preference back to new style
                        if (pref.getDependency() == LATEST_CATEGORY)
                            pref.setStyle(UpdatePreference.STYLE_NEW);
                        else
                            pref.setStyle(UpdatePreference.STYLE_OLD);

                        // We are OK to stop download, trigger it
                        mDownloadManager.remove(mDownloadId);
                        mUpdateHandler.removeCallbacks(mUpdateProgress);
                        resetDownloadState();

                        // Clear the stored data from shared preferences
                        mPrefs.edit()
                                .remove(Constants.DOWNLOAD_ID)
                                .remove(Constants.DOWNLOAD_MD5)
                                .apply();

                        Toast.makeText(UpdatesSettings.this,
                                R.string.download_cancelled, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void checkForDownloadCompleted(Intent intent) {
        if (intent == null) {
            return;
        }

        long downloadId = intent.getLongExtra(EXTRA_FINISHED_DOWNLOAD_ID, -1);
        if (downloadId < 0) {
            return;
        }

        String fullPathName = intent.getStringExtra(EXTRA_FINISHED_DOWNLOAD_PATH);
        if (fullPathName == null) {
            return;
        }

        String fileName = new File(fullPathName).getName();

        // Find the matching preference so we can retrieve the UpdateInfo
        UpdatePreference pref = (UpdatePreference) mUpdatesList.findPreference(fileName);
        if (pref != null) {
            pref.setStyle(UpdatePreference.STYLE_DOWNLOADED);
            onStartUpdate(pref);
        }

        resetDownloadState();
    }

    private void resetDownloadState() {
        mDownloadId = -1;
        mFileName = null;
        mDownloading = false;
        mDownloadingPreference = null;
    }

    private String mapCheckValue(Integer value) {
        Resources resources = getResources();
        String[] checkNames = resources.getStringArray(R.array.update_check_entries);
        String[] checkValues = resources.getStringArray(R.array.update_check_values);
        for (int i = 0; i < checkValues.length; i++) {
            if (Integer.decode(checkValues[i]).equals(value)) {
                return checkNames[i];
            }
        }
        return getString(R.string.unknown);
    }

    private void checkForUpdates() {
        if (mProgressDialog != null) {
            return;
        }

        // If there is no internet connection, display a message and return.
        if (!Utils.isOnline(this)) {
            Toast.makeText(this, R.string.data_connection_required, Toast.LENGTH_SHORT).show();
            return;
        }

        mProgressDialog = new ProgressDialog(this);
        mProgressDialog.setTitle(R.string.checking_for_updates);
        mProgressDialog.setMessage(getString(R.string.checking_for_updates));
        mProgressDialog.setIndeterminate(true);
        mProgressDialog.setCancelable(true);
        mProgressDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                Intent cancelIntent = new Intent(UpdatesSettings.this, UpdateCheckService.class);
                cancelIntent.setAction(UpdateCheckService.ACTION_CANCEL_CHECK);
                startService(cancelIntent);
                mProgressDialog = null;
            }
        });

        Intent checkIntent = new Intent(UpdatesSettings.this, UpdateCheckService.class);
        checkIntent.setAction(UpdateCheckService.ACTION_CHECK);
        startService(checkIntent);

        mProgressDialog.show();
    }

    private void updateLayout() {
        // Read existing Updates
        LinkedList<String> existingFiles = new LinkedList<String>();

        mUpdateFolder = Utils.makeUpdateFolder();
        File[] files = mUpdateFolder.listFiles(new UpdateFilter(".zip"));

        if (mUpdateFolder.exists() && mUpdateFolder.isDirectory() && files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    existingFiles.add(file.getName());
                }
            }
        }

        // Clear the notification if one exists
        Utils.cancelNotification(this);

        // Build list of updates
        LinkedList<UpdateInfo> availableUpdates = State.loadState(this);
        final LinkedList<UpdateInfo> updates = new LinkedList<UpdateInfo>();
        int numOfUpdates = 0;

        for (String fileName : existingFiles) {
            updates.add(new UpdateInfo(fileName));
            numOfUpdates++;
        }
        for (UpdateInfo update : availableUpdates) {
            // Only add updates to the list that are not already downloaded
            if (existingFiles.contains(update.getFileName()) || numOfUpdates > 10) {
                continue;
            }
            updates.add(update);
            numOfUpdates++;
        }

        Collections.sort(updates, new Comparator<UpdateInfo>() {
            @Override
            public int compare(UpdateInfo lhs, UpdateInfo rhs) {
                // sort in descending 'UI name' order (newest first)
                return -lhs.getName().compareTo(rhs.getName());
            }
        });

        // Update the preference list
        refreshPreferences(updates);

        // Prune obsolete change log files
        new Thread() {
            @Override
            public void run() {
                File[] files = getCacheDir().listFiles(new UpdateFilter(".changelog"));
                if (files == null) {
                    return;
                }

                for (File file : files) {
                    boolean updateExists = false;
                    for (UpdateInfo info : updates) {
                        if (file.getName().startsWith(info.getFileName())) {
                            updateExists = true;
                            break;
                        }
                    }
                    if (!updateExists) {
                        file.delete();
                    }
                }
            }
        }.start();
    }

    private void refreshPreferences(LinkedList<UpdateInfo> updates) {
        if (mUpdatesList == null) {
            return;
        }
        if (isMLatestListRemoved) {
            PreferenceScreen preferenceScreen = (PreferenceScreen) findPreference("exodus_updater_screen");
            preferenceScreen.addPreference(mLatestList);
            mUpdatesList.setTitle(R.string.previous_updates_title);
            isMLatestListRemoved = false;
        }
        // Clear the list
        mUpdatesList.removeAll();
        // Clear the Latest List.
        mLatestList.removeAll();

        // Convert the installed version name to the associated filename
        String installedZip = "exodus-" + Utils.getInstalledVersion() + ".zip";

        boolean isFirstDownload = true;

        // Add the updates
        for (UpdateInfo ui : updates) {
            // Determine the preference style and create the preference
            boolean isDownloading = ui.getFileName().equals(mFileName);
            int style;

            if (isDownloading) {
                // In progress download
                style = UpdatePreference.STYLE_DOWNLOADING;
            } else if (ui.getFileName().equals(installedZip)) {
                // This is the currently installed version
                style = UpdatePreference.STYLE_INSTALLED;
            } else if (ui.getDownloadUrl() != null && isFirstDownload) {
                style = UpdatePreference.STYLE_NEW;
            } else if (ui.getDownloadUrl() != null) {
                style = UpdatePreference.STYLE_OLD;
            } else {
                style = UpdatePreference.STYLE_DOWNLOADED;
            }

            UpdatePreference up = new UpdatePreference(this, ui, style);
            up.setOnActionListener(this);
            up.setKey(ui.getFileName());

            // If we have an in progress download, link the preference
            if (isDownloading) {
                mDownloadingPreference = up;
                up.setOnReadyListener(this);
                mDownloading = true;
            }

            if(isFirstDownload){
                mLatestList.addPreference(up);
                up.setDependency(LATEST_CATEGORY);
                isFirstDownload = false;
            } else {
                // Add to the list
                mUpdatesList.addPreference(up);
                up.setDependency(UPDATES_CATEGORY);
            }
        }

        // If no updates are in the list, show the default message
        if (mUpdatesList.getPreferenceCount() == 0) {
            Preference pref = new Preference(this);
            pref.setLayoutResource(R.layout.preference_empty_list);
            pref.setTitle(R.string.no_available_updates_intro);
            pref.setEnabled(false);
            mUpdatesList.setTitle(R.string.update_not_available);
            mUpdatesList.addPreference(pref);
            PreferenceScreen preferenceScreen = (PreferenceScreen) findPreference("exodus_updater_screen");
            preferenceScreen.removePreference(mLatestList);
            isMLatestListRemoved = true;
        }
    }

    @Override
    public void onDeleteUpdate(UpdatePreference pref) {
        final String fileName = pref.getKey();

        if (mUpdateFolder.exists() && mUpdateFolder.isDirectory()) {
            File zipFileToDelete = new File(mUpdateFolder, fileName);

            if (zipFileToDelete.exists()) {
                zipFileToDelete.delete();
            } else {
                Log.d(TAG, "Update to delete not found");
                return;
            }

            String message = getString(R.string.delete_single_update_success_message, fileName);
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        } else if (!mUpdateFolder.exists()) {
            Toast.makeText(this, R.string.delete_updates_noFolder_message, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.delete_updates_failure_message, Toast.LENGTH_SHORT).show();
        }

        // Update the list
        updateLayout();
    }

    private void confirmDeleteAll() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_delete_dialog_title)
                .setMessage(R.string.confirm_delete_all_dialog_message)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // We are OK to delete, trigger it
                        deleteOldUpdates();
                        updateLayout();
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private boolean deleteOldUpdates() {
        boolean success;
        //mUpdateFolder: Foldername with fullpath of SDCARD
        if (mUpdateFolder.exists() && mUpdateFolder.isDirectory()) {
            deleteDir(mUpdateFolder);
            mUpdateFolder.mkdir();
            success = true;
            Toast.makeText(this, R.string.delete_updates_success_message, Toast.LENGTH_SHORT).show();
        } else if (!mUpdateFolder.exists()) {
            success = false;
            Toast.makeText(this, R.string.delete_updates_noFolder_message, Toast.LENGTH_SHORT).show();
        } else {
            success = false;
            Toast.makeText(this, R.string.delete_updates_failure_message, Toast.LENGTH_SHORT).show();
        }
        return success;
    }

    private static boolean deleteDir(File dir) {
        if (dir.isDirectory()) {
            String[] children = dir.list();
            for (String aChildren : children) {
                boolean success = deleteDir(new File(dir, aChildren));
                if (!success) {
                    return false;
                }
            }
        }
        // The directory is now empty so delete it
        return dir.delete();
    }

    private void showSysInfo() {
        // Build the message
        Date lastCheck = new Date(mPrefs.getLong(Constants.LAST_UPDATE_CHECK_PREF, 0));
        String date = DateFormat.getLongDateFormat(this).format(lastCheck);
        String time = DateFormat.getTimeFormat(this).format(lastCheck);

        String message = getString(R.string.sysinfo_device) + " " + Utils.getDeviceType() + "\n\n"
                + getString(R.string.sysinfo_running) + " " + Utils.getInstalledVersion() + "\n\n"
                + getString(R.string.sysinfo_last_check) + " " + date + " " + time;

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.menu_system_info)
                .setMessage(message)
                .setPositiveButton(R.string.dialog_ok, null);

        AlertDialog dialog = builder.create();
        dialog.show();

        TextView messageView = (TextView) dialog.findViewById(android.R.id.message);
        messageView.setTextAppearance(this, android.R.style.TextAppearance_DeviceDefault_Small);
    }

    private void showGappsLink() {
        final Resources resource = UpdatesSettings.this.getResources();
        final String[] gappsURLEntries = resource.getStringArray(R.array.gapps_entries);
        final String[] gappsURLLink = resource.getStringArray(R.array.gapps_entries_url);
        final CharSequence[] modes = new CharSequence[gappsURLEntries.length];
        for(int i=0; i< modes.length;i++) {
            modes[i] = gappsURLEntries[i];
        }
        final String url = resource.getString(R.string.gapps_url);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(R.string.gapps_menu_title)
                .setSingleChoiceItems(modes, 0,
            new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    selected = which;
                }
            })
            .setPositiveButton(R.string.dialog_ok, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.setData(Uri.parse(gappsURLLink[selected]));
                    startActivity(intent);
                }
            })
            .setNegativeButton(R.string.dialog_cancel, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    dialog.dismiss();
                }
            });
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    @Override
    public void onStartUpdate(UpdatePreference pref) {
        final UpdateInfo updateInfo = pref.getUpdateInfo();

        // Prevent the dialog from being triggered more than once
        if (mStartUpdateVisible) {
            return;
        }

        mStartUpdateVisible = true;

        // Get the message body right
        String dialogBody = getString(R.string.apply_update_dialog_text, updateInfo.getFileName());

        // Display the dialog
        new AlertDialog.Builder(this)
                .setTitle(R.string.apply_update_dialog_title)
                .setMessage(dialogBody)
                .setPositiveButton(R.string.dialog_update, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            Utils.triggerUpdate(UpdatesSettings.this, updateInfo.getFileName());
                        } catch (IOException e) {
                            Log.e(TAG, "Unable to reboot into recovery mode", e);
                            Toast.makeText(UpdatesSettings.this, R.string.apply_unable_to_reboot_toast,
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        mStartUpdateVisible = false;
                    }
                })
                .show();
    }
}