package com.bytesforge.linkasanote.laano;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.databinding.DataBindingUtil;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.StringRes;
import android.support.annotation.VisibleForTesting;
import android.support.design.widget.AppBarLayout;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.NavigationView;
import android.support.design.widget.Snackbar;
import android.support.design.widget.TabLayout;
import android.support.test.espresso.IdlingResource;
import android.support.v4.app.ActivityCompat;
import android.support.v4.view.GravityCompat;
import android.support.v4.view.ViewPager;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;

import com.bytesforge.linkasanote.BaseFragment;
import com.bytesforge.linkasanote.LaanoApplication;
import com.bytesforge.linkasanote.R;
import com.bytesforge.linkasanote.about.AboutActivity;
import com.bytesforge.linkasanote.data.source.local.LocalContract;
import com.bytesforge.linkasanote.databinding.ActivityLaanoBinding;
import com.bytesforge.linkasanote.laano.favorites.FavoritesPresenter;
import com.bytesforge.linkasanote.laano.favorites.FavoritesPresenterModule;
import com.bytesforge.linkasanote.laano.links.LinksPresenter;
import com.bytesforge.linkasanote.laano.links.LinksPresenterModule;
import com.bytesforge.linkasanote.laano.notes.NotesPresenter;
import com.bytesforge.linkasanote.laano.notes.NotesPresenterModule;
import com.bytesforge.linkasanote.manageaccounts.ManageAccountsActivity;
import com.bytesforge.linkasanote.settings.Settings;
import com.bytesforge.linkasanote.settings.SettingsActivity;
import com.bytesforge.linkasanote.sync.SyncNotifications;
import com.bytesforge.linkasanote.utils.AppBarLayoutOnStateChangeListener;
import com.bytesforge.linkasanote.utils.CloudUtils;
import com.bytesforge.linkasanote.utils.EspressoIdlingResource;

import java.io.IOException;

import javax.inject.Inject;

import static com.bytesforge.linkasanote.utils.CloudUtils.getAccountType;
import static com.google.common.base.Preconditions.checkNotNull;

public class LaanoActivity extends AppCompatActivity implements
        ActivityCompat.OnRequestPermissionsResultCallback {

    private static final String TAG = LaanoActivity.class.getSimpleName();

    private static final String STATE_ACTIVE_TAB = "ACTIVE_TAB";

    private static final int REQUEST_GET_ACCOUNTS = 0;
    private static final String PERMISSION_GET_ACCOUNTS = Manifest.permission.GET_ACCOUNTS;
    private static String[] PERMISSIONS_GET_ACCOUNTS = {PERMISSION_GET_ACCOUNTS};

    private static final int ACTION_MANAGE_ACCOUNTS = 1;

    @Inject
    LinksPresenter linksPresenter;

    @Inject
    FavoritesPresenter favoritesPresenter;

    @Inject
    NotesPresenter notesPresenter;

    @Inject
    LaanoUiManager laanoUiManager;

    @Inject
    AccountManager accountManager;

    @Inject
    Settings settings;

    private int activeTab;
    private SyncBroadcastReceiver syncBroadcastReceiver;
    private ActivityLaanoBinding binding;
    private LaanoViewModel viewModel;

    @Override
    protected void onStart() {
        Log.i(TAG, "onStart()");
        if (Settings.GLOBAL_CLIPBOARD_ON_START) {
            // NOTE: application context
            startClipboardService();
        } // NOTE: else it will be started with the first launch of the addEdit... activity
        super.onStart();
    }

    @Override
    protected void onResume() {
        Log.i(TAG, "onResume()");
        super.onResume();

        notifyTabSelected(activeTab);
        laanoUiManager.updateTitle(activeTab);

        IntentFilter syncIntentFilter = new IntentFilter();
        syncIntentFilter.addAction(SyncNotifications.ACTION_SYNC);
        syncIntentFilter.addAction(SyncNotifications.ACTION_SYNC_LINKS);
        syncIntentFilter.addAction(SyncNotifications.ACTION_SYNC_FAVORITES);
        syncIntentFilter.addAction(SyncNotifications.ACTION_SYNC_NOTES);
        syncBroadcastReceiver = new SyncBroadcastReceiver();
        registerReceiver(syncBroadcastReceiver, syncIntentFilter);
    }

    @Override
    protected void onPause() {
        Log.i(TAG, "onPause()");
        if (syncBroadcastReceiver != null) {
            unregisterReceiver(syncBroadcastReceiver);
            syncBroadcastReceiver = null;
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        Log.i(TAG, "onDestroy()");
        if (!isChangingConfigurations()) {
            stopClipboardService();
        }
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "onCreate()");
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            applyInstanceState(getDefaultInstanceState());
        } else {
            applyInstanceState(savedInstanceState);
        }
        TagsBindingAdapter.invalidateTagsViewWidths();
        binding = DataBindingUtil.setContentView(this, R.layout.activity_laano);
        viewModel = new LaanoViewModel(this);
        viewModel.setInstanceState(savedInstanceState);
        binding.setViewModel(viewModel);

        // Toolbar
        setSupportActionBar(binding.toolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setHomeAsUpIndicator(R.drawable.ic_menu);
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
        // Navigation Drawer
        setupDrawerLayout(binding.drawerLayout);
        setupDrawerContent(binding.navView);
        // ViewPager
        LaanoFragmentPagerAdapter pagerAdapter = new LaanoFragmentPagerAdapter(
                getSupportFragmentManager(), getApplicationContext());
        ViewPager viewPager = binding.laanoViewPager;
        setupViewPager(viewPager, pagerAdapter);
        setCurrentTab(activeTab);
        viewPager.setOffscreenPageLimit(2);
        // TabLayout
        setupTabLayout(binding.tabLayout, viewPager);
        // Presenters
        LaanoApplication application = (LaanoApplication) getApplication();
        application.getApplicationComponent()
                .getLaanoComponent(
                        new LinksPresenterModule(this, pagerAdapter.getLinksFragment()),
                        new FavoritesPresenterModule(this, pagerAdapter.getFavoritesFragment()),
                        new NotesPresenterModule(this, pagerAdapter.getNotesFragment()),
                        new LaanoUiManagerModule(this, binding.tabLayout,
                                binding.navView.getMenu(), viewModel.headerViewModel, pagerAdapter))
                .inject(this);
        // FAB
        setupFabAdd(binding.fabAdd);
        // AppBar
        setupAppBarLayout(binding.appBarLayout, binding.fabAdd);
        // UiManager
        linksPresenter.updateTabNormalState();
        favoritesPresenter.updateTabNormalState();
        notesPresenter.updateTabNormalState();
        updateDefaultAccount();
    }

    private void startClipboardService() {
        Intent intent = new Intent(getApplicationContext(), ClipboardService.class);
        startService(intent);
    }

    private void stopClipboardService() {
        Intent intent = new Intent(getApplicationContext(), ClipboardService.class);
        stopService(intent);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_ACTIVE_TAB, activeTab);
        viewModel.saveInstanceState(outState);
    }

    private void applyInstanceState(@NonNull Bundle state) {
        checkNotNull(state);
        activeTab = state.getInt(STATE_ACTIVE_TAB);
    }

    private Bundle getDefaultInstanceState() {
        Bundle defaultState = new Bundle();
        defaultState.putInt(STATE_ACTIVE_TAB, 0);
        return defaultState;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        DrawerLayout drawerLayout = binding.drawerLayout;
        switch (item.getItemId()) {
            case android.R.id.home:
                drawerLayout.openDrawer(GravityCompat.START);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void triggerSync() {
        Account account = CloudUtils.getDefaultAccount(this, accountManager);
        if (account == null) return;

        Bundle extras = new Bundle();
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
        ContentResolver.requestSync(account, LocalContract.CONTENT_AUTHORITY, extras);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (ACTION_MANAGE_ACCOUNTS == requestCode && RESULT_OK == resultCode
                && data.getBooleanExtra(ManageAccountsActivity.KEY_ACCOUNT_LIST_CHANGED, false)) {
            updateDefaultAccount();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.toolbar_laano, menu);
        return true;
    }

    private void updateDefaultAccount() {
        Account account = CloudUtils.getDefaultAccount(this, accountManager);
        laanoUiManager.updateDefaultAccount(account);
        laanoUiManager.updateLastSyncStatus();
    }

    // Public

    public void setCurrentTab(int tab) {
        ViewPager viewPager = binding.laanoViewPager;
        viewPager.setCurrentItem(tab);
    }

    // Get Accounts Permission

    public void checkGetAccountsPermissionAndLaunchActivity() {
        if (ActivityCompat.checkSelfPermission(this, PERMISSION_GET_ACCOUNTS)
                != PackageManager.PERMISSION_GRANTED) {
            requestGetAccountsPermission();
        } else {
            startManageAccountsActivity();
        }
    }

    private void requestGetAccountsPermission() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, PERMISSION_GET_ACCOUNTS)) {
            Snackbar.make(binding.laanoViewPager,
                    R.string.laano_permission_get_accounts,
                    Snackbar.LENGTH_INDEFINITE)
                    .setAction(R.string.snackbar_button_ok, view ->
                            requestPermissions(PERMISSIONS_GET_ACCOUNTS, REQUEST_GET_ACCOUNTS))
                    .show();
        } else {
            requestPermissions(PERMISSIONS_GET_ACCOUNTS, REQUEST_GET_ACCOUNTS);
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_GET_ACCOUNTS) {
            if (grantResults.length == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startManageAccountsActivity();
            } else {
                showPermissionDeniedSnackbar();
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    private void notifyTabSelected(int position) {
        switch (position) {
            case LaanoFragmentPagerAdapter.LINKS_TAB:
                linksPresenter.onTabSelected();
                break;
            case LaanoFragmentPagerAdapter.FAVORITES_TAB:
                favoritesPresenter.onTabSelected();
                break;
            case LaanoFragmentPagerAdapter.NOTES_TAB:
                notesPresenter.onTabSelected();
                break;
            default:
                throw new IllegalStateException("Unexpected tab was selected");
        }
    }

    private void notifyTabDeselected(int position) {
        switch (position) {
            case LaanoFragmentPagerAdapter.LINKS_TAB:
                linksPresenter.onTabDeselected();
                break;
            case LaanoFragmentPagerAdapter.FAVORITES_TAB:
                favoritesPresenter.onTabDeselected();
                break;
            case LaanoFragmentPagerAdapter.NOTES_TAB:
                notesPresenter.onTabDeselected();
                break;
            default:
                throw new IllegalStateException("Unexpected tab was selected");
        }
    }

    // Broadcast Receiver

    private class SyncBroadcastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            int status = intent.getIntExtra(SyncNotifications.EXTRA_STATUS, -1);
            String id = intent.getStringExtra(SyncNotifications.EXTRA_ID);

            if (action.equals(SyncNotifications.ACTION_SYNC)) {
                switch (status) {
                    case SyncNotifications.STATUS_SYNC_START:
                        laanoUiManager.setSyncDrawerMenu();
                        break;
                    case SyncNotifications.STATUS_SYNC_STOP:
                        laanoUiManager.updateLastSyncStatus();
                        laanoUiManager.setNormalDrawerMenu();
                        break;
                }
                return;
            }
            laanoUiManager.setSyncDrawerMenu(); // NOTE: if the first SYNC_START was missed
            int tabPosition;
            switch (action) {
                case SyncNotifications.ACTION_SYNC_LINKS:
                    tabPosition = LaanoFragmentPagerAdapter.LINKS_TAB;
                    if (status == SyncNotifications.STATUS_SYNC_STOP) {
                        linksPresenter.loadLinks(true);
                        linksPresenter.updateTabNormalState();
                    }
                    break;
                case SyncNotifications.ACTION_SYNC_FAVORITES:
                    tabPosition = LaanoFragmentPagerAdapter.FAVORITES_TAB;
                    if (status == SyncNotifications.STATUS_SYNC_STOP) {
                        favoritesPresenter.loadFavorites(true);
                        favoritesPresenter.updateTabNormalState();
                    }
                    break;
                case SyncNotifications.ACTION_SYNC_NOTES:
                    tabPosition = LaanoFragmentPagerAdapter.NOTES_TAB;
                    if (status == SyncNotifications.STATUS_SYNC_STOP) {
                        notesPresenter.loadNotes(true);
                        linksPresenter.loadLinks(true);
                        notesPresenter.updateTabNormalState();
                    }
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Unexpected action has been received in SyncBroadcastReceiver [" + action + "]");
            }
            if (status == SyncNotifications.STATUS_SYNC_START) {
                laanoUiManager.setTabSyncState(tabPosition);
            } else if (status == SyncNotifications.STATUS_UPLOADED) {
                laanoUiManager.incUploaded(tabPosition);
            } else if (status == SyncNotifications.STATUS_DOWNLOADED) {
                laanoUiManager.incDownloaded(tabPosition);
            }
            if (tabPosition == activeTab) {
                laanoUiManager.updateTitle(activeTab);
            }
        } // onReceive
    }

    // Setup

    private void setupAppBarLayout(
            @NonNull AppBarLayout appBarLayout, @NonNull FloatingActionButton fab) {
        checkNotNull(appBarLayout);
        checkNotNull(fab);
        appBarLayout.addOnOffsetChangedListener(new AppBarLayoutOnStateChangeListener() {

            @Override
            public void onStateChanged(AppBarLayout appBarLayout, State state) {
                if (state == State.EXPANDED && !fab.isShown()) {
                    fab.show();
                } else if (state == State.COLLAPSED && fab.isShown()) {
                    fab.hide();
                }
            }
        });
    }

    private void setupViewPager(
            @NonNull ViewPager viewPager, @NonNull LaanoFragmentPagerAdapter pagerAdapter) {
        checkNotNull(viewPager);
        checkNotNull(pagerAdapter);
        viewPager.setAdapter(pagerAdapter);
        // NOTE: Fragments are needed immediately to build Presenters
        pagerAdapter.instantiateItem(viewPager, LaanoFragmentPagerAdapter.LINKS_TAB);
        pagerAdapter.instantiateItem(viewPager, LaanoFragmentPagerAdapter.FAVORITES_TAB);
        pagerAdapter.instantiateItem(viewPager, LaanoFragmentPagerAdapter.NOTES_TAB);
        pagerAdapter.finishUpdate(viewPager);
        // Listener
        viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {

            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
            }

            @Override
            public void onPageSelected(int position) {
                if (activeTab != position) {
                    notifyTabDeselected(activeTab);
                    notifyTabSelected(position);
                    laanoUiManager.updateTitle(position);
                    activeTab = position;
                }
            }

            @Override
            public void onPageScrollStateChanged(int state) {
            }
        });
    }

    private void setupDrawerLayout(@NonNull final DrawerLayout drawerLayout) {
        checkNotNull(drawerLayout);
        // NOTE: untestable behavior (swipeRight() doesn't open Drawer)
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        ActionBarDrawerToggle drawerToggle = new ActionBarDrawerToggle(
                this, drawerLayout, R.string.drawer_open, R.string.drawer_close) {

            @Override
            public void onDrawerOpened(View drawerView) {
                super.onDrawerOpened(drawerView);
                drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
            }

            @Override
            public void onDrawerClosed(View drawerView) {
                super.onDrawerClosed(drawerView);
                drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
            }
        };
        drawerLayout.addDrawerListener(drawerToggle);
    }

    private void startManageAccountsActivity() {
        Intent manageAccountsIntent =
                new Intent(getApplicationContext(), ManageAccountsActivity.class);
        startActivityForResult(manageAccountsIntent, ACTION_MANAGE_ACCOUNTS);
    }

    private void startSettingsActivity() {
        Intent settingsIntent = new Intent(this, SettingsActivity.class);
        settingsIntent.putExtra(SettingsActivity.EXTRA_ACCOUNT,
                CloudUtils.getDefaultAccount(this, accountManager));
        startActivity(settingsIntent);
    }

    private void startAboutActivity() {
        Intent settingsIntent = new Intent(this, AboutActivity.class);
        startActivity(settingsIntent);
    }

    private void setupDrawerContent(@NonNull NavigationView navigationView) {
        checkNotNull(navigationView);
        DrawerLayout drawerLayout = binding.drawerLayout;
        navigationView.setNavigationItemSelectedListener(
                (menuItem) -> {
                    switch (menuItem.getItemId()) {
                        case R.id.add_account_menu_item:
                            accountManager.addAccount(getAccountType(this),
                                    null, null, null, this, addAccountCallback, new Handler());
                            break;
                        case R.id.manage_accounts_menu_item:
                            checkGetAccountsPermissionAndLaunchActivity();
                            break;
                        case R.id.sync_menu_item:
                            if (!CloudUtils.isApplicationConnected(this)) {
                                showApplicationOfflineSnackbar();
                            } else {
                                triggerSync();
                            }
                            break;
                        case R.id.settings_menu_item:
                            startSettingsActivity();
                            break;
                        case R.id.about_menu_item:
                            startAboutActivity();
                            break;
                    }
                    drawerLayout.closeDrawers();
                    return true;
                }
        );
    }

    private void setupTabLayout(@NonNull TabLayout tabLayout, @NonNull ViewPager viewPager) {
        checkNotNull(tabLayout);
        checkNotNull(viewPager);
        tabLayout.setupWithViewPager(viewPager);
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {

            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                setCurrentTab(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });
    }

    private void setupFabAdd(@NonNull FloatingActionButton fab) {
        checkNotNull(fab);
        fab.setOnClickListener(v -> {
                    switch (activeTab) {
                        case LaanoFragmentPagerAdapter.LINKS_TAB:
                            linksPresenter.addLink();
                            break;
                        case LaanoFragmentPagerAdapter.FAVORITES_TAB:
                            favoritesPresenter.addFavorite();
                            break;
                        case LaanoFragmentPagerAdapter.NOTES_TAB:
                            notesPresenter.addNote();
                            break;
                        default:
                            throw new IllegalStateException("Unexpected tab was selected");
                    }
                }
        );
    }

    // Callbacks

    private AccountManagerCallback<Bundle> addAccountCallback = future -> {
        try {
            future.getResult(); // NOTE: see exceptions
            updateDefaultAccount();
            showAccountSuccessfullyAddedSnackbar();
        } catch (OperationCanceledException e) {
            Log.d(TAG, "Account creation was canceled by user");
        } catch (IOException | AuthenticatorException e) {
            Log.e(TAG, "Account creation was finished with an exception", e);
            Throwable throwable = e.getCause();
            if (throwable != null) {
                Snackbar.make(binding.laanoViewPager, throwable.getMessage(), Snackbar.LENGTH_LONG)
                        .show();
            }
        }
    };

    // SnackBars

    private void showPermissionDeniedSnackbar() {
        showSnackbar(R.string.snackbar_no_permission, Snackbar.LENGTH_LONG);
    }

    private void showAccountSuccessfullyAddedSnackbar() {
        showSnackbar(R.string.laano_account_added, Snackbar.LENGTH_LONG);
    }

    private void showApplicationOfflineSnackbar() {
        showSnackbar(R.string.laano_offline, Snackbar.LENGTH_LONG);
    }

    private void showSnackbar(@StringRes int stringId, int duration) {
        Snackbar.make(binding.laanoViewPager, stringId, duration).show();
    }

    // Testing

    @VisibleForTesting
    public BaseFragment getCurrentFragment() {
        ViewPager viewPager = binding.laanoViewPager;
        int position = viewPager.getCurrentItem();
        LaanoFragmentPagerAdapter adapter = (LaanoFragmentPagerAdapter) viewPager.getAdapter();

        return adapter.getFragment(position);
    }

    @VisibleForTesting
    public IdlingResource getCountingIdlingResource() {
        return EspressoIdlingResource.getIdlingResource();
    }
}
