package com.bytesforge.linkasanote.settings;

import android.accounts.Account;
import android.content.ContentResolver;
import android.content.Context;
import android.content.PeriodicSync;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Bundle;
import android.support.annotation.NonNull;

import com.bytesforge.linkasanote.R;
import com.bytesforge.linkasanote.data.source.local.LocalContract;
import com.bytesforge.linkasanote.laano.FilterType;
import com.bytesforge.linkasanote.sync.SyncAdapter;
import com.bytesforge.linkasanote.sync.files.JsonFile;

import java.util.List;

import io.reactivex.Single;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.System.currentTimeMillis;

public class Settings {

    public static final float GLOBAL_IMAGE_BUTTON_ALPHA_DISABLED = 0.3f;
    public static final float GLOBAL_PROGRESS_OVERLAY_ALPHA = 0.4f;
    public static final long GLOBAL_PROGRESS_OVERLAY_DURATION = 200; // ms
    public static final long GLOBAL_PROGRESS_OVERLAY_SHOW_DELAY = 200; // ms
    public static final boolean GLOBAL_ITEM_CLICK_SELECT_FILTER = true;

    private static final String DEFAULT_SYNC_DIRECTORY = "/.laano_sync";
    private static final boolean DEFAULT_EXPAND_LINKS = false;
    private static final boolean DEFAULT_EXPAND_NOTES = false;
    private static final boolean DEFAULT_CLIPBOARD_MONITOR = true;

    private static final String SETTING_LAST_SYNC_TIME = "LAST_SYNC_TIME";
    private static final String SETTING_LAST_SYNC_STATUS = "LAST_SYNC_STATUS";
    private static final String SETTING_LINK_FILTER = "LINK_FILTER";
    private static final String SETTING_FAVORITE_FILTER = "FAVORITE_FILTER";
    private static final String SETTING_NOTE_FILTER = "NOTE_FILTER";

    private static final long DEFAULT_LAST_SYNC_TIME = 0;
    private static final int DEFAULT_LAST_SYNC_STATUS = SyncAdapter.LAST_SYNC_STATUS_UNKNOWN;
    public static final FilterType DEFAULT_FILTER_TYPE = FilterType.ALL;
    private static final String DEFAULT_LAST_SYNCED_ETAG  = null;
    private static final String DEFAULT_LINK_FILTER = null;
    private static final String DEFAULT_FAVORITE_FILTER = null;
    private static final String DEFAULT_NOTE_FILTER = null;

    private final Resources resources;
    private final SharedPreferences sharedPreferences;

    public Settings(Context context, SharedPreferences sharedPreferences) {
        resources = context.getResources();
        this.sharedPreferences = sharedPreferences;
    }

    public boolean isExpandLinks() {
        return sharedPreferences.getBoolean(
                resources.getString(R.string.pref_key_expand_links), DEFAULT_EXPAND_LINKS);
    }

    public boolean isExpandNotes() {
        return sharedPreferences.getBoolean(
                resources.getString(R.string.pref_key_expand_notes), DEFAULT_EXPAND_NOTES);
    }

    public boolean isClipboardMonitor() {
        return sharedPreferences.getBoolean(
                resources.getString(R.string.pref_key_clipboard_monitor), DEFAULT_CLIPBOARD_MONITOR);
    }

    public String getSyncDirectory() {
        String syncDirectory = sharedPreferences.getString(
                resources.getString(R.string.pref_key_sync_directory), DEFAULT_SYNC_DIRECTORY);
        return syncDirectory.startsWith(JsonFile.PATH_SEPARATOR)
                ? syncDirectory
                : JsonFile.PATH_SEPARATOR + syncDirectory;
    }

    @NonNull
    Single<Long> getSyncInterval(Account account, boolean isDelay) {
        return Single.fromCallable(() -> {
            if (account == null) return null;

            if (ContentResolver.getIsSyncable(account, LocalContract.CONTENT_AUTHORITY) <= 0) {
                return null;
            }
            // NOTE: getPeriodSyncs returns old value if is called immediately after addPeriodSync
            if (isDelay) {
                Thread.sleep(25); // TODO: find a better way, it is not good at all
            }
            Long manualInterval = Long.parseLong(
                    resources.getString(R.string.pref_sync_interval_manual_mode));
            if (ContentResolver.getSyncAutomatically(account, LocalContract.CONTENT_AUTHORITY)) {
                List<PeriodicSync> periodicSyncs = ContentResolver
                        .getPeriodicSyncs(account, LocalContract.CONTENT_AUTHORITY);

                if (periodicSyncs.isEmpty()) return manualInterval;
                else return periodicSyncs.get(0).period;
            } else {
                return manualInterval;
            }
        });
    }

    void setSyncInterval(Account account, long seconds) {
        if (account == null) return;

        Long manualInterval = Long.parseLong(resources.getString(
                R.string.pref_sync_interval_manual_mode));
        if (seconds == manualInterval) {
            ContentResolver.setSyncAutomatically(account, LocalContract.CONTENT_AUTHORITY, false);
        } else {
            ContentResolver.setSyncAutomatically(account, LocalContract.CONTENT_AUTHORITY, true);
            ContentResolver.addPeriodicSync(
                    account, LocalContract.CONTENT_AUTHORITY, new Bundle(), seconds);
        }
    }

    public int getLastSyncStatus() {
        return sharedPreferences.getInt(SETTING_LAST_SYNC_STATUS, DEFAULT_LAST_SYNC_STATUS);
    }

    public synchronized void setLastSyncStatus(int lastSyncStatus) {
        putIntSetting(SETTING_LAST_SYNC_STATUS, lastSyncStatus);
    }

    public long getLastSyncTime() {
        return sharedPreferences.getLong(SETTING_LAST_SYNC_TIME, DEFAULT_LAST_SYNC_TIME);
    }

    public synchronized void updateLastSyncTime() {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putLong(SETTING_LAST_SYNC_TIME, currentTimeMillis());
        editor.apply();
    }

    public String getLastSyncedETag(@NonNull String key) {
        return sharedPreferences.getString(checkNotNull(key), DEFAULT_LAST_SYNCED_ETAG);
    }

    public void setLastSyncedETag(@NonNull String key, String lastSyncedETag) {
        putStringSetting(checkNotNull(key), lastSyncedETag);
    }

    public FilterType getFilterType(@NonNull String key) {
        checkNotNull(key);

        int ordinal = sharedPreferences.getInt(key, DEFAULT_FILTER_TYPE.ordinal());
        try {
            return FilterType.values()[ordinal];
        } catch (ArrayIndexOutOfBoundsException e) {
            return DEFAULT_FILTER_TYPE;
        }
    }

    public synchronized void setFilterType(@NonNull String key, FilterType filterType) {
        checkNotNull(key);
        if (filterType == null) return;

        FilterType filter = getFilterType(key);
        if (filterType != filter) {
            putIntSetting(key, filterType.ordinal());
        }
    }

    public String getLinkFilter() {
        return sharedPreferences.getString(SETTING_LINK_FILTER, DEFAULT_LINK_FILTER);
    }

    public synchronized void setLinkFilter(String linkId) {
        String filter = getLinkFilter();
        if (filter == null && linkId == null) return;

        if (filter == null ^ linkId == null) {
            putStringSetting(SETTING_LINK_FILTER, linkId);
        } else if (!linkId.equals(filter)) {
            putStringSetting(SETTING_LINK_FILTER, linkId);
        }
    }

    public void resetLinkFilter(String linkId) {
        String filter = getLinkFilter();
        if (filter == null || linkId == null) return;

        if (filter.equals(linkId)) {
            setLinkFilter(null);
        }
    }

    public String getFavoriteFilter() {
        return sharedPreferences.getString(SETTING_FAVORITE_FILTER, DEFAULT_FAVORITE_FILTER);
    }

    public synchronized void setFavoriteFilter(String favoriteId) {
        String filter = getFavoriteFilter();
        if (filter == null && favoriteId == null) return;

        if (filter == null ^ favoriteId == null) {
            putStringSetting(SETTING_FAVORITE_FILTER, favoriteId);
        } else if (!favoriteId.equals(filter)) {
            putStringSetting(SETTING_FAVORITE_FILTER, favoriteId);
        }
    }

    public void resetFavoriteFilter(String favoriteId) {
        String filter = getFavoriteFilter();
        if (filter == null || favoriteId == null) return;

        if (filter.equals(favoriteId)) {
            setFavoriteFilter(null);
        }
    }

    public String getNoteFilter() {
        return sharedPreferences.getString(SETTING_NOTE_FILTER, DEFAULT_NOTE_FILTER);
    }

    public synchronized void setNoteFilter(String noteId) {
        String filter = getNoteFilter();
        if (filter == null && noteId == null) return;

        if (filter == null ^ noteId == null) {
            putStringSetting(SETTING_NOTE_FILTER, noteId);
        } else if (!noteId.equals(filter)) {
            putStringSetting(SETTING_NOTE_FILTER, noteId);
        }
    }

    public void resetNoteFilter(String noteId) {
        String filter = getNoteFilter();
        if (filter == null || noteId == null) return;

        if (filter.equals(noteId)) {
            setNoteFilter(null);
        }
    }

    private void putStringSetting(String key, String value) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(key, value);
        editor.apply();
    }

    private void putIntSetting(String key, int value) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt(key, value);
        editor.apply();
    }
}
