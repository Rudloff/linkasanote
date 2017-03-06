package com.bytesforge.linkasanote.data.source.local;

import android.content.ContentResolver;
import android.content.Context;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.LargeTest;
import android.support.test.runner.AndroidJUnit4;

import com.bytesforge.linkasanote.TestUtils;
import com.bytesforge.linkasanote.data.Favorite;
import com.bytesforge.linkasanote.utils.schedulers.BaseSchedulerProvider;
import com.bytesforge.linkasanote.utils.schedulers.ImmediateSchedulerProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;

import io.reactivex.observers.TestObserver;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class LocalDataSourceTest {

    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final BaseSchedulerProvider schedulerProvider;
    private final ContentResolver contentResolver;
    private LocalDataSource localDataSource;

    private List<Favorite> FAVORITES;
    private TestObserver<List<Favorite>> testFavoritesObserver;
    private TestObserver<Favorite> testFavoriteObserver;

    public LocalDataSourceTest() {
        schedulerProvider = new ImmediateSchedulerProvider();
        contentResolver = context.getContentResolver();
    }

    @Before
    public void setupLocalDataSource() {
        FAVORITES = TestUtils.buildFavorites();
        localDataSource = new LocalDataSource(contentResolver);
        cleanupLocalDataSource();
    }

    @After
    public void cleanupLocalDataSource() {
        localDataSource.deleteAllLinks();
        localDataSource.deleteAllFavorites();
        localDataSource.deleteAllNotes();
        localDataSource.deleteAllTags();
    }

    @Test
    public void saveFavorite_retrievesFavorite() {
        Favorite favorite = FAVORITES.get(0);
        // Preconditions
        testFavoriteObserver = localDataSource.getFavorite(favorite.getId()).test();
        testFavoriteObserver.assertError(NoSuchElementException.class);
        // Test
        localDataSource.saveFavorite(favorite);
        testFavoriteObserver = localDataSource.getFavorite(favorite.getId()).test();
        testFavoriteObserver.assertValue(favorite);

    }

    @Test
    public void getFavorites_retrievesSavedFavorites() {
        // Preconditions
        testFavoritesObserver = localDataSource.getFavorites().test();
        testFavoritesObserver.assertValue(Collections.emptyList());
        for (Favorite favorite : FAVORITES) {
            localDataSource.saveFavorite(favorite);
        }
        // Test
        testFavoritesObserver = localDataSource.getFavorites().test();
        testFavoritesObserver.assertValue(FAVORITES);
    }

    @Test
    public void deleteAllFavorites_emptyListOfRetrievedFavorites() {
        // Preconditions
        for (Favorite favorite : FAVORITES) {
            localDataSource.saveFavorite(favorite);
        }
        testFavoritesObserver = localDataSource.getFavorites().test();
        testFavoritesObserver.assertValue(FAVORITES);
        // Test
        localDataSource.deleteAllFavorites();
        testFavoritesObserver = localDataSource.getFavorites().test();
        testFavoritesObserver.assertValue(Collections.emptyList());
    }

    @Test
    public void deleteFavorite_remainsListOfOtherFavorites() {
        // Preconditions
        for (Favorite favorite : FAVORITES) {
            localDataSource.saveFavorite(favorite);
        }
        testFavoritesObserver = localDataSource.getFavorites().test();
        testFavoritesObserver.assertValue(FAVORITES);
        // Test
        Favorite favorite = FAVORITES.remove(0);
        localDataSource.deleteFavorite(favorite.getId());
        testFavoritesObserver = localDataSource.getFavorites().test();
        testFavoritesObserver.assertValue(FAVORITES);
    }
}