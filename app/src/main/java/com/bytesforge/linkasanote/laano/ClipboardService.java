package com.bytesforge.linkasanote.laano;

import android.app.Service;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.StringRes;
import android.util.Log;
import android.util.Patterns;
import android.widget.Toast;

import com.bytesforge.linkasanote.LaanoApplication;
import com.bytesforge.linkasanote.R;
import com.bytesforge.linkasanote.settings.Settings;
import com.bytesforge.linkasanote.utils.CommonUtils;
import com.bytesforge.linkasanote.utils.schedulers.BaseSchedulerProvider;
import com.google.common.base.Strings;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.Arrays;

import javax.inject.Inject;

import io.reactivex.Single;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

import static com.google.common.base.Preconditions.checkNotNull;

public class ClipboardService extends Service {

    private static final String TAG = ClipboardService.class.getSimpleName();
    // NOTE: when text is copied from Chrome browser onPrimaryClipChanged is called twice
    private static final long INVALIDATE_CACHE_IN_MILLIS = 200;

    public interface Callback {

        void onClipboardChanged(int clipboardType);
        void onClipboardLinkExtraReady();
    }

    private final IBinder binder = new ClipboardBinder();

    @Inject
    Settings settings;

    @Inject
    BaseSchedulerProvider schedulerProvider;

    private Resources resources;
    private ClipboardService.Callback callback;
    private CompositeDisposable compositeDisposable;
    private ClipboardManager clipboardManager;

    public static final int CLIPBOARD_EMPTY = 0;
    public static final int CLIPBOARD_TEXT = 1;
    public static final int CLIPBOARD_LINK = 2;
    public static final int CLIPBOARD_EXTRA = 3;

    private ClipboardManager.OnPrimaryClipChangedListener primaryClipChangedListener = this::clipboardCheck;

    private boolean cacheIsDirty = true;
    private final Object cacheIsDirtyLock = new Object();
    private boolean startedByCommand = false;

    private int clipboardType = CLIPBOARD_EMPTY;
    private String normalizedClipboard;
    private boolean linkDisabled;
    private String linkTitle;
    private String linkDescription;
    private String[] linkKeywords;

    public class ClipboardBinder extends Binder {

        public ClipboardService getService() {
            return ClipboardService.this;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        callback = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        LaanoApplication application = (LaanoApplication) getApplication();
        application.getApplicationComponent().inject(this);
        resources = getResources();
        compositeDisposable = new CompositeDisposable();
        clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboardManager.addPrimaryClipChangedListener(primaryClipChangedListener);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!startedByCommand) {
            startedByCommand = true;
            clipboardCheck();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        clipboardManager.removePrimaryClipChangedListener(primaryClipChangedListener);
        // NOTE: thread is continue running when service is destroyed
        compositeDisposable.clear();
        /* NOTE: annoying and not very useful notification
        if (settings.isClipboardLinkGetMetadata()) {
            // NOTE: inform only if internet connection is involved
            String message = resources.getString(
                    R.string.clipboard_service_stopped, resources.getString(R.string.app_name));
            Toast.makeText(ClipboardService.this, message, Toast.LENGTH_SHORT).show();
        }*/
        super.onDestroy();
    }

    public void setCallback(ClipboardService.Callback callback) {
        this.callback = callback;
        notifySubscriber();
    }

    private void cleanup() {
        compositeDisposable.clear();
        clipboardType = CLIPBOARD_EMPTY;
        normalizedClipboard = null;
        linkDisabled = false;
        linkTitle = null;
        linkDescription = null;
        linkKeywords = null;
    }

    private boolean isCacheDirty() {
        Thread invalidateCache = new Thread(() -> {
            synchronized (cacheIsDirtyLock) {
                cacheIsDirty = true;
            }
        });
        synchronized (cacheIsDirtyLock) {
            if (cacheIsDirty) {
                cacheIsDirty = false;
                new Handler().postDelayed(invalidateCache, INVALIDATE_CACHE_IN_MILLIS);
                return true;
            }
        }
        return false;
    }

    private void normalizeClipboard(@NonNull final String data) {
        checkNotNull(data);
        String clipboard = data.trim();
        if (clipboard.isEmpty()) return;

        if (Patterns.WEB_URL.matcher(clipboard).matches()) {
            if (!settings.isClipboardLinkFollow()) {
                clipboard = normalizeUrl(clipboard);
            } // NOTE: else URL will be normalized in loadLinkExtra()
            clipboardType = CLIPBOARD_LINK;
        } else {
            clipboardType = CLIPBOARD_TEXT;
        }
        normalizedClipboard = clipboard;
    }

    private String normalizeUrl(@NonNull final String url) {
        Uri uri = Uri.parse(url);
        // NOTE: fragment part is omitted
        Uri.Builder uriBuilder = new Uri.Builder()
                .scheme(uri.getScheme())
                .authority(uri.getAuthority())
                .path(uri.getPath());
        for (String parameterName : settings.getClipboardParameterWhiteListArray()) {
            String parameterValue = uri.getQueryParameter(parameterName);
            if (parameterValue != null) {
                uriBuilder.appendQueryParameter(parameterName, parameterValue);
            }
        }
        return uriBuilder.build().toString();
    }

    private void loadLinkExtra(@NonNull final String link) {
        checkNotNull(link);
        Disposable disposable = Single
                .fromCallable(() -> Jsoup.connect(link)
                        .followRedirects(settings.isClipboardLinkFollow())
                        .maxBodySize(Settings.GLOBAL_LINK_MAX_BODY_SIZE_BYTES)
                        .get())
                .subscribeOn(schedulerProvider.io())
                .observeOn(schedulerProvider.ui())
                .subscribe(document -> {
                    normalizedClipboard = normalizeUrl(document.location());
                    String title = document.title();
                    linkTitle = Strings.isNullOrEmpty(title) ? null : title;
                    linkDescription = selectMeta(document, "description");
                    String keywords = selectMeta(document, "keywords");
                    if (keywords != null) {
                        String[] keywordList = keywords.split(
                                "\\s*,\\s*", Settings.GLOBAL_LINK_MAX_KEYWORDS + 1);
                        if (keywordList.length > Settings.GLOBAL_LINK_MAX_KEYWORDS) {
                            linkKeywords = Arrays.copyOf(
                                    keywordList, Settings.GLOBAL_LINK_MAX_KEYWORDS);
                        } else {
                            linkKeywords = keywordList;
                        }
                    }
                    notifySubscriber();
                    Log.i(TAG, "URL [" + normalizedClipboard + "]");
                    Log.i(TAG, "Title [" + linkTitle + "]");
                    Log.i(TAG, "Description [" + linkDescription + "]");
                    Log.i(TAG, "Keywords [" + Arrays.toString(linkKeywords) + "]");
                    if (Settings.GLOBAL_CLIPBOARD_LINK_UPDATED_TOAST) {
                        @StringRes int messageId;
                        if (isLinkExtra()) {
                            messageId = R.string.clipboard_service_extra_ready;
                        } else {
                            messageId = R.string.clipboard_service_extra_empty;
                        }
                        Toast.makeText(ClipboardService.this, messageId, Toast.LENGTH_SHORT).show();
                    }
                }, throwable -> {
                    CommonUtils.logStackTrace(TAG, throwable);
                    normalizedClipboard = normalizeUrl(normalizedClipboard);
                    linkDisabled = true;
                    linkTitle = null;
                    linkDescription = null;
                    linkKeywords = null;
                    notifySubscriber();
                    if (Settings.GLOBAL_CLIPBOARD_LINK_UPDATED_TOAST) {
                        Toast.makeText(ClipboardService.this,
                                R.string.clipboard_service_extra_failed,
                                Toast.LENGTH_SHORT).show();
                    }
                });
        compositeDisposable.add(disposable);
    }

    private String selectMeta(@NonNull final Document document, @NonNull final String metaName) {
        checkNotNull(document);
        checkNotNull(metaName);
        Element metaElement = document.select("meta[name=" + metaName + "]").first();
        String metaValue = metaElement == null ? null : metaElement.attr("content");
        if (Strings.isNullOrEmpty(metaValue)) {
            return null;
        }
        return metaValue.trim();
    }

    private void clipboardCheck() {
        if (!isCacheDirty()) return;
        Log.i(TAG, "ClipboardCheck()");

        cleanup();
        if (clipboardManager.hasPrimaryClip()) {
            ClipData primaryClip = clipboardManager.getPrimaryClip();
            ClipDescription description = primaryClip.getDescription();
            if (primaryClip.getItemCount() > 0
                    && (description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)
                    || description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML))) {
                CharSequence text = primaryClip.getItemAt(0).getText();
                if (text != null) {
                    normalizeClipboard(text.toString());
                }
            }
        }
        notifySubscriber();
        if (settings.isClipboardLinkGetMetadata() && clipboardType == CLIPBOARD_LINK) {
            loadLinkExtra(normalizedClipboard);
        }
    }

    private void notifySubscriber() {
        if (callback == null) return;

        if (isLinkExtra()) {
            callback.onClipboardLinkExtraReady();
        } else {
            callback.onClipboardChanged(clipboardType);
        }
    }

    private boolean isLinkExtra() {
        return linkDisabled || linkTitle != null || linkDescription != null
                || (linkKeywords != null && linkKeywords.length > 0);
    }

    public boolean isStartedByCommand() {
        return startedByCommand;
    }

    public int getClipboardType() {
        return clipboardType;
    }

    public int getClipboardState() {
        return isLinkExtra() ? CLIPBOARD_EXTRA : clipboardType;
    }

    public String getNormalizedClipboard() {
        return normalizedClipboard;
    }

    public boolean isLinkDisabled() {
        return linkDisabled;
    }

    public String getLinkTitle() {
        return linkTitle;
    }

    public String getLinkDescription() {
        return linkDescription;
    }

    public String[] getLinkKeywords() {
        return linkKeywords;
    }
}