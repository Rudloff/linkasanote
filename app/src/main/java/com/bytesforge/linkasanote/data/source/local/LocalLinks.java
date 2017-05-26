package com.bytesforge.linkasanote.data.source.local;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.support.annotation.NonNull;

import com.bytesforge.linkasanote.data.Item;
import com.bytesforge.linkasanote.data.LinkFactory;
import com.bytesforge.linkasanote.data.Note;
import com.bytesforge.linkasanote.data.Tag;
import com.bytesforge.linkasanote.sync.SyncState;
import com.bytesforge.linkasanote.utils.CommonUtils;
import com.google.common.collect.ObjectArrays;

import java.util.List;
import java.util.NoSuchElementException;

import io.reactivex.Observable;
import io.reactivex.Single;

import static com.google.common.base.Preconditions.checkNotNull;

public class LocalLinks<T extends Item> implements LocalItem<T> {

    private static final String TAG = LocalLinks.class.getSimpleName();

    // NOTE: static fails Mockito to mock this class
    private final Uri LINK_URI;
    private final ContentResolver contentResolver;
    private final LocalTags localTags;
    private final LocalNotes<Note> localNotes;
    private final LinkFactory<T> factory;

    public LocalLinks(
            @NonNull ContentResolver contentResolver,
            @NonNull LocalTags localTags, @NonNull LocalNotes<Note> localNotes,
            @NonNull LinkFactory<T> factory) {
        this.contentResolver = checkNotNull(contentResolver);
        this.localTags = checkNotNull(localTags);
        this.localNotes = checkNotNull(localNotes);
        this.factory = checkNotNull(factory);
        LINK_URI = LocalContract.LinkEntry.buildUri();
    }

    private Single<T> buildLink(final T link) {
        Single<T> singleLink = Single.just(link);
        Uri linkTagUri = LocalContract.LinkEntry.buildTagsDirUriWith(link.getRowId());
        Single<List<Tag>> singleTags = localTags.getTags(linkTagUri).toList();

        // OPTIMIZATION: the requested Note can be replaced with the cached one in repository or vice versa
        Uri linkNoteUri = LocalContract.LinkEntry.buildNotesDirUriWith(link.getId());
        Single<List<Note>> singleNotes = localNotes.get(linkNoteUri).toList();
        return Single.zip(singleLink, singleTags, singleNotes, factory::build);
    }

    // Operations

    @Override
    public Observable<T> getAll() {
        return get(LINK_URI, null, null, null);
    }

    @Override
    public Observable<T> getActive() {
        return getActive(null);
    }

    @Override
    public Observable<T> getActive(final String[] linkIds) {
        String selection = LocalContract.LinkEntry.COLUMN_NAME_DELETED + " = ?" +
                " OR " + LocalContract.LinkEntry.COLUMN_NAME_CONFLICTED + " = ?";
        String[] selectionArgs = {"0", "1"};
        final String sortOrder = LocalContract.LinkEntry.COLUMN_NAME_CREATED + " DESC";

        int size = linkIds == null ? 0 : linkIds.length;
        if (size > 0) {
            selection = " (" + selection + ") AND " + LocalContract.LinkEntry.COLUMN_NAME_ENTRY_ID +
                    " IN (" + CommonUtils.strRepeat("?", size, ", ") + ")";
            selectionArgs = ObjectArrays.concat(selectionArgs, linkIds, String.class);
        }
        return get(LINK_URI, selection, selectionArgs, sortOrder);
    }

    @Override
    public Observable<T> getUnsynced() {
        final String selection = LocalContract.LinkEntry.COLUMN_NAME_SYNCED + " = ?";
        final String[] selectionArgs = {"0"};

        return get(LINK_URI, selection, selectionArgs, null);
    }

    @Override
    public Observable<T> get(final Uri uri) {
        final String sortOrder = LocalContract.TagEntry.COLUMN_NAME_CREATED + " DESC";
        return get(uri, null, null, sortOrder);
    }

    @Override
    public Observable<T> get(
            final Uri uri,
            final String selection, final String[] selectionArgs, final String sortOrder) {
        Observable<T> linksGenerator = Observable.generate(() -> {
            return contentResolver.query(
                    uri, LocalContract.LinkEntry.LINK_COLUMNS,
                    selection, selectionArgs, sortOrder);
        }, (cursor, linkEmitter) -> {
            if (cursor == null) {
                linkEmitter.onError(new NullPointerException("An error while retrieving the cursor"));
                return null;
            }
            if (cursor.moveToNext()) {
                linkEmitter.onNext(factory.from(cursor));
            } else {
                linkEmitter.onComplete();
            }
            return cursor;
        }, Cursor::close);
        return linksGenerator.flatMap(link -> buildLink(link).toObservable());
    }

    @Override
    public Single<T> get(final String linkId) {
        return Single.fromCallable(() -> {
            try (Cursor cursor = contentResolver.query(
                    LocalContract.LinkEntry.buildUriWith(linkId),
                    LocalContract.LinkEntry.LINK_COLUMNS, null, null, null)) {
                if (cursor == null) return null;

                if (!cursor.moveToLast()) {
                    throw new NoSuchElementException("The requested link was not found");
                }
                return factory.from(cursor);
            }
        }).flatMap(this::buildLink);
    }

    /**
     * @return Returns true if all tags were successfully saved
     */
    private Single<Boolean> saveTags(final long linkRowId, final List<Tag> tags) {
        if (linkRowId <= 0) {
            return Single.just(false);
        } else if (tags == null) {
            return Single.just(true);
        }
        Uri linkTagUri = LocalContract.LinkEntry.buildTagsDirUriWith(linkRowId);
        return Observable.fromIterable(tags)
                .flatMap(tag -> localTags.saveTag(tag, linkTagUri).toObservable())
                .count()
                .map(count -> count == tags.size())
                .onErrorReturn(throwable -> {
                    CommonUtils.logStackTrace(TAG, throwable);
                    return false;
                });
    }

    @Override
    public Single<Boolean> save(final T link) {
        return Single.fromCallable(() -> {
            ContentValues values = link.getContentValues();
            Uri linkUri = contentResolver.insert(LINK_URI, values);
            if (linkUri == null) {
                throw new NullPointerException("Provider must return URI or throw exception");
            }
            String rowId = LocalContract.LinkEntry.getIdFrom(linkUri);
            return Long.parseLong(rowId);
        }).flatMap(rowId -> saveTags(rowId, link.getTags()));
    }

    @Override
    public Single<Boolean> saveDuplicated(final T link) {
        return getNextDuplicated(link.getDuplicatedKey())
                .flatMap(duplicated -> {
                    SyncState state = new SyncState(link.getETag(), duplicated);
                    return Single.just(factory.build(link, state));
                }).flatMap(this::save);
    }

    @Override
    public Single<Boolean> update(final String linkId, final SyncState state) {
        return Single.fromCallable(() -> {
            ContentValues values = state.getContentValues();
            Uri uri = LocalContract.LinkEntry.buildUriWith(linkId);
            return contentResolver.update(uri, values, null, null);
        }).map(numRows -> numRows == 1);
    }

    @Override
    public Single<Integer> resetSyncState() {
        return Single.fromCallable(() -> {
            ContentValues values = new ContentValues();
            values.put(LocalContract.LinkEntry.COLUMN_NAME_ETAG, (String) null);
            values.put(LocalContract.LinkEntry.COLUMN_NAME_SYNCED, false);
            final String selection = LocalContract.LinkEntry.COLUMN_NAME_SYNCED + " = ?";
            final String[] selectionArgs = {"1"};
            return contentResolver.update(LINK_URI, values, selection, selectionArgs);
        });
    }

    @Override
    public Single<Boolean> delete(final String linkId) {
        Uri uri = LocalContract.LinkEntry.buildUriWith(linkId);
        return Single.fromCallable(() -> contentResolver.delete(uri, null, null))
                .map(numRows -> numRows == 1);
    }

    @Override
    public Single<Integer> delete() {
        return Single.fromCallable(() -> contentResolver.delete(LINK_URI, null, null));
    }

    @Override
    public Single<SyncState> getSyncState(final String linkId) {
        Uri uri = LocalContract.LinkEntry.buildUriWith(linkId);
        return LocalDataSource.getSyncState(contentResolver, uri);
    }

    @Override
    public Observable<SyncState> getSyncStates() {
        return LocalDataSource.getSyncStates(contentResolver, LINK_URI, null, null, null);
    }

    @Override
    public Observable<String> getIds() {
        return LocalDataSource.getIds(contentResolver, LINK_URI);
    }

    @Override
    public Single<Boolean> isConflicted() {
        return LocalDataSource.isConflicted(contentResolver, LINK_URI);
    }

    @Override
    public Single<Boolean> isUnsynced() {
        return LocalDataSource.isUnsynced(contentResolver, LINK_URI);
    }

    private Single<Integer> getNextDuplicated(final String duplicatedKey) {
        final String[] columns = new String[]{
                "MAX(" + LocalContract.LinkEntry.COLUMN_NAME_DUPLICATED + ") + 1"};
        final String selection = LocalContract.LinkEntry.COLUMN_NAME_LINK + " = ?";
        final String[] selectionArgs = {duplicatedKey};

        return Single.fromCallable(() -> {
            try (Cursor cursor = contentResolver.query(
                    LINK_URI, columns, selection, selectionArgs, null)) {
                if (cursor == null) return null;

                if (cursor.moveToLast()) {
                    return cursor.getInt(0);
                }
                return 0;
            }
        });
    }

    @Override
    public Single<T> getMain(final String duplicatedKey) {
        final String selection = LocalContract.LinkEntry.COLUMN_NAME_LINK + " = ?" +
                " AND " + LocalContract.LinkEntry.COLUMN_NAME_DUPLICATED + " = ?";
        final String[] selectionArgs = {duplicatedKey, "0"};

        return Single.fromCallable(() -> {
            try (Cursor cursor = contentResolver.query(
                    LINK_URI, LocalContract.LinkEntry.LINK_COLUMNS,
                    selection, selectionArgs, null)) {
                if (cursor == null) {
                    return null; // NOTE: NullPointerException
                } else if (!cursor.moveToLast()) {
                    throw new NoSuchElementException("The requested link was not found");
                }
                return factory.from(cursor);
            }
        }).flatMap(this::buildLink);
    }

    /**
     * @return Returns true if conflict has been successfully resolved
     */
    @Override
    public Single<Boolean> autoResolveConflict(final String linkId) {
        return get(linkId)
                .map(link -> {
                    if (!link.isDuplicated()) {
                        return !link.isConflicted();
                    }
                    try {
                        getMain(link.getDuplicatedKey()).blockingGet();
                        return false;
                    } catch (NoSuchElementException e) {
                        SyncState state = new SyncState(SyncState.State.SYNCED);
                        return update(linkId, state).blockingGet();
                    }
                });
    }
}
