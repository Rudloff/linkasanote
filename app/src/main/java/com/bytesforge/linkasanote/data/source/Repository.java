package com.bytesforge.linkasanote.data.source;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;

import com.bytesforge.linkasanote.data.Favorite;
import com.bytesforge.linkasanote.data.Link;
import com.bytesforge.linkasanote.data.Note;
import com.bytesforge.linkasanote.data.Tag;

import java.security.InvalidParameterException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Singleton;

import io.reactivex.Observable;
import io.reactivex.Single;

import static com.bytesforge.linkasanote.utils.UuidUtils.isKeyValidUuid;
import static com.google.common.base.Preconditions.checkNotNull;

@Singleton
public class Repository implements DataSource {

    private final DataSource localDataSource;
    private final DataSource cloudDataSource;

    // TODO: change to links
    @VisibleForTesting
    @Nullable
    Map<String, Link> cachedLinks;

    @VisibleForTesting
    @Nullable
    Map<String, Favorite> cachedFavorites;

    @VisibleForTesting
    @Nullable
    Map<String, Link> cachedNotes;

    @VisibleForTesting
    @Nullable
    Map<String, Tag> cachedTags;

    @VisibleForTesting
    public boolean linkCacheIsDirty = false;

    @VisibleForTesting
    public boolean favoriteCacheIsDirty = false;

    @VisibleForTesting
    public boolean noteCacheIsDirty = false;

    //@Inject NOTE: @Provides is needed for testing to mock Repository
    public Repository(DataSource localDataSource, DataSource cloudDataSource) {
        this.localDataSource = localDataSource;
        this.cloudDataSource = cloudDataSource;
    }

    // Links

    @Override
    public Observable<Link> getLinks() {
        if (!linkCacheIsDirty && cachedLinks != null) {
            return Observable.fromIterable(cachedLinks.values());
        }
        return getAndCacheLocalLinks();
    }

    private Observable<Link> getAndCacheLocalLinks() {
        if (cachedLinks == null) {
            cachedLinks = new LinkedHashMap<>();
        }
        if (cachedTags == null) {
            cachedTags = new LinkedHashMap<>();
        }
        cachedLinks.clear();
        return localDataSource.getLinks()
                .doOnComplete(() -> linkCacheIsDirty = false)
                .doOnNext(link -> {
                    cachedLinks.put(link.getId(), link);
                    List<Tag> tags = link.getTags();
                    if (tags != null) {
                        for (Tag tag : tags) cachedTags.put(tag.getName(), tag);
                    }
                });
    }

    @Override
    public Single<Link> getLink(@NonNull String linkId) {
        checkNotNull(linkId);

        if (!isKeyValidUuid(linkId)) {
            throw new InvalidParameterException("getLink() called with invalid UUID");
        }
        return getLink(linkId, false);
    }

    public Single<Link> getLink(@NonNull String linkId, boolean forceCacheUpdate) {
        checkNotNull(linkId);

        if (forceCacheUpdate) return getAndCacheLocalLink(linkId);

        final Link cachedLink = getCachedLink(linkId);
        if (cachedLink != null) {
            return Single.just(cachedLink);
        }
        return getAndCacheLocalLink(linkId);
    }

    private Single<Link> getAndCacheLocalLink(String linkId) {
        if (cachedLinks == null) {
            cachedLinks = new LinkedHashMap<>();
        }
        if (cachedTags == null) {
            cachedTags = new LinkedHashMap<>();
        }
        return localDataSource.getLink(linkId)
                .doOnSuccess(link -> {
                    cachedLinks.put(linkId, link);
                    // NOTE: because order of Links is messed up
                    linkCacheIsDirty = true;
                    List<Tag> tags = link.getTags();
                    if (tags != null) {
                        for (Tag tag : tags) cachedTags.put(tag.getName(), tag);
                    }
                });
    }

    @Nullable
    private Link getCachedLink(@NonNull String id) {
        checkNotNull(id);

        if (cachedLinks != null && !cachedLinks.isEmpty()) {
            return cachedLinks.get(id);
        }
        return null;
    }

    @Override
    public void saveLink(@NonNull Link link) {
        checkNotNull(link);

        localDataSource.saveLink(link); // blocking operation
        cloudDataSource.saveLink(link);

        // Link
        if (cachedLinks == null) {
            cachedLinks = new LinkedHashMap<>();
        }
        // NOTE: new Link has no rowId to bind to RecyclerView and position is unknown
        linkCacheIsDirty = true;
        // Tags
        if (cachedTags == null) {
            cachedTags = new LinkedHashMap<>();
        }
        List<Tag> tags = link.getTags();
        if (tags != null) {
            for (Tag tag : tags) {
                cachedTags.put(tag.getName(), tag);
            }
        }
    }

    @Override
    public void deleteAllLinks() {
        localDataSource.deleteAllLinks(); // blocking
        //cloudDataSource.deleteAllLinks();

        if (cachedLinks == null) {
            cachedLinks = new LinkedHashMap<>();
        }
        cachedLinks.clear();
    }

    @Override
    public void deleteLink(@NonNull String linkId) {
        checkNotNull(linkId);

        localDataSource.deleteLink(linkId); // blocking
        cloudDataSource.deleteLink(linkId);

        deleteCachedLink(linkId);
    }

    @Override
    public Single<Boolean> isConflictedLinks() {
        return localDataSource.isConflictedLinks();
    }

    // TODO: implement cache invalidation for one specific item
    public void refreshLinks() {
        linkCacheIsDirty = true;
    }

    public void deleteCachedLink(@NonNull String linkId) {
        checkNotNull(linkId);

        if (cachedLinks == null) {
            cachedLinks = new LinkedHashMap<>();
        }
        cachedLinks.remove(linkId);
    }

    // Notes

    @Override
    public Single<List<Note>> getNotes() {
        return null;
    }

    @Override
    public Single<Note> getNote(@NonNull String noteId) {
        return null;
    }

    @Override
    public void saveNote(@NonNull Note note) {
    }

    @Override
    public void deleteAllNotes() {
        localDataSource.deleteAllNotes();
        cloudDataSource.deleteAllNotes();

        if (cachedNotes == null) {
            cachedNotes = new LinkedHashMap<>();
        }
        cachedNotes.clear();
    }

    // Favorites

    @Override
    public Observable<Favorite> getFavorites() {
        if (!favoriteCacheIsDirty && cachedFavorites != null) {
            return Observable.fromIterable(cachedFavorites.values());
        }
        return getAndCacheLocalFavorites();
    }

    private Observable<Favorite> getAndCacheLocalFavorites() {
        if (cachedFavorites == null) {
            cachedFavorites = new LinkedHashMap<>();
        }
        if (cachedTags == null) {
            cachedTags = new LinkedHashMap<>();
        }
        cachedFavorites.clear();
        return localDataSource.getFavorites()
                .doOnComplete(() -> favoriteCacheIsDirty = false)
                .doOnNext(favorite -> {
                    cachedFavorites.put(favorite.getId(), favorite);
                    List<Tag> tags = favorite.getTags();
                    if (tags != null) {
                        for (Tag tag : tags) cachedTags.put(tag.getName(), tag);
                    }
                });
    }

    @Override
    public Single<Favorite> getFavorite(@NonNull String favoriteId) {
        checkNotNull(favoriteId);

        if (!isKeyValidUuid(favoriteId)) {
            throw new InvalidParameterException("getFavorite() called with invalid UUID");
        }
        return getFavorite(favoriteId, false);
    }

    public Single<Favorite> getFavorite(@NonNull String favoriteId, boolean forceCacheUpdate) {
        checkNotNull(favoriteId);

        if (forceCacheUpdate) return getAndCacheLocalFavorite(favoriteId);

        final Favorite cachedFavorite = getCachedFavorite(favoriteId);
        if (cachedFavorite != null) {
            return Single.just(cachedFavorite);
        }
        return getAndCacheLocalFavorite(favoriteId);
    }

    private Single<Favorite> getAndCacheLocalFavorite(String favoriteId) {
        if (cachedFavorites == null) {
            cachedFavorites = new LinkedHashMap<>();
        }
        if (cachedTags == null) {
            cachedTags = new LinkedHashMap<>();
        }
        return localDataSource.getFavorite(favoriteId)
                .doOnSuccess(favorite -> {
                    cachedFavorites.put(favoriteId, favorite);
                    // NOTE: because order of Favorites is messed up
                    favoriteCacheIsDirty = true;
                    List<Tag> tags = favorite.getTags();
                    if (tags != null) {
                        for (Tag tag : tags) cachedTags.put(tag.getName(), tag);
                    }
                });
    }

    @Nullable
    private Favorite getCachedFavorite(@NonNull String id) {
        checkNotNull(id);

        if (cachedFavorites != null && !cachedFavorites.isEmpty()) {
            return cachedFavorites.get(id);
        }
        return null;
    }

    @Override
    public void saveFavorite(@NonNull Favorite favorite) {
        checkNotNull(favorite);

        localDataSource.saveFavorite(favorite); // blocking operation
        cloudDataSource.saveFavorite(favorite);

        // Favorite
        if (cachedFavorites == null) {
            cachedFavorites = new LinkedHashMap<>();
        }
        // NOTE: new Favorite has no rowId to bind to RecyclerView and position is unknown
        favoriteCacheIsDirty = true;
        // Tags
        if (cachedTags == null) {
            cachedTags = new LinkedHashMap<>();
        }
        List<Tag> tags = favorite.getTags();
        if (tags != null) {
            for (Tag tag : tags) {
                cachedTags.put(tag.getName(), tag);
            }
        }
    }

    @Override
    public void deleteAllFavorites() {
        localDataSource.deleteAllFavorites(); // blocking
        //cloudDataSource.deleteAllFavorites();

        if (cachedFavorites == null) {
            cachedFavorites = new LinkedHashMap<>();
        }
        cachedFavorites.clear();
    }

    @Override
    public void deleteFavorite(@NonNull String favoriteId) {
        checkNotNull(favoriteId);

        localDataSource.deleteFavorite(favoriteId); // blocking
        cloudDataSource.deleteFavorite(favoriteId);

        deleteCachedFavorite(favoriteId);
    }

    @Override
    public Single<Boolean> isConflictedFavorites() {
        return localDataSource.isConflictedFavorites();
    }

    // TODO: implement cache invalidation for one specific item
    public void refreshFavorites() {
        favoriteCacheIsDirty = true;
    }

    public void deleteCachedFavorite(@NonNull String favoriteId) {
        checkNotNull(favoriteId);

        if (cachedFavorites == null) {
            cachedFavorites = new LinkedHashMap<>();
        }
        cachedFavorites.remove(favoriteId);
    }

    // Tags: tag is part of the object and should be bound with the object

    @Override
    public Observable<Tag> getTags() {
        if (cachedTags != null) {
            return Observable.fromIterable(cachedTags.values());
        }
        return getAndCacheLocalTags();
    }

    private Observable<Tag> getAndCacheLocalTags() {
        if (cachedTags == null) {
            cachedTags = new LinkedHashMap<>();
        }
        return localDataSource.getTags()
                .doOnNext(tag -> cachedTags.put(tag.getName(), tag));
    }

    @Override
    public Single<Tag> getTag(@NonNull String tagName) {
        checkNotNull(tagName);

        final Tag cachedTag = getCachedTag(tagName);
        if (cachedTag != null) {
            return Single.just(cachedTag);
        }

        if (cachedTags == null) {
            cachedTags = new LinkedHashMap<>();
        }
        return localDataSource.getTag(tagName)
                .doOnSuccess(tag -> cachedTags.put(tagName, tag));
    }

    @Nullable
    private Tag getCachedTag(@NonNull String name) {
        checkNotNull(name);

        if (cachedTags == null || cachedTags.isEmpty()) {
            return null;
        } else {
            return cachedTags.get(name);
        }
    }

    @Override
    public void saveTag(@NonNull Tag tag) {
        checkNotNull(tag);

        localDataSource.saveTag(tag);
        if (cachedTags == null) {
            cachedTags = new LinkedHashMap<>();
        }
        cachedTags.put(tag.getName(), tag);
    }

    @Override
    public void deleteAllTags() {
        localDataSource.deleteAllTags();
        if (cachedTags == null) {
            cachedTags = new LinkedHashMap<>();
        }
        cachedTags.clear();
    }
}
