package com.bytesforge.linkasanote.data.source.local;

import android.net.Uri;

import com.bytesforge.linkasanote.sync.SyncState;

import io.reactivex.Observable;
import io.reactivex.Single;

public interface LocalItem<T> {

    Observable<T> getAll();
    Observable<T> getActive();
    Observable<T> getUnsynced();
    Observable<T> get(final Uri uri);
    Observable<T> get(
            final Uri uri,
            final String selection, final String[] selectionArgs, final String sortOrder);
    Single<T> get(final String itemId);
    Single<Long> save(final T item);
    Single<Long> saveDuplicated(final T item);
    Single<Integer> update(final String itemId, final SyncState state);
    Single<Integer> resetSyncState();
    Single<Integer> delete(final String itemId);
    Single<Integer> delete();
    Single<SyncState> getSyncState(final String itemId);
    Observable<SyncState> getSyncStates();
    Observable<String> getIds();
    Single<Boolean> isConflicted();
    Single<T> getMain(final String duplicatedKey);
    Single<Boolean> autoResolveConflict(final String linkId);
}