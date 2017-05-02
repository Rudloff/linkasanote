package com.bytesforge.linkasanote.laano.favorites;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.DialogFragment;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.view.ActionMode;
import android.support.v7.widget.DividerItemDecoration;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.PopupMenu;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.SearchView;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import com.bytesforge.linkasanote.BaseFragment;
import com.bytesforge.linkasanote.R;
import com.bytesforge.linkasanote.data.Favorite;
import com.bytesforge.linkasanote.databinding.FragmentLaanoFavoritesBinding;
import com.bytesforge.linkasanote.laano.FilterType;
import com.bytesforge.linkasanote.laano.favorites.addeditfavorite.AddEditFavoriteActivity;
import com.bytesforge.linkasanote.laano.favorites.addeditfavorite.AddEditFavoriteFragment;
import com.bytesforge.linkasanote.laano.favorites.conflictresolution.FavoritesConflictResolutionDialog;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;

public class FavoritesFragment extends BaseFragment implements FavoritesContract.View {

    public static final int REQUEST_ADD_FAVORITE = 1;
    public static final int REQUEST_EDIT_FAVORITE = 2;
    public static final int REQUEST_FAVORITE_CONFLICT_RESOLUTION = 3;

    private FavoritesContract.Presenter presenter;
    private FavoritesContract.ViewModel viewModel;
    private FavoritesAdapter adapter;
    private ActionMode actionMode;
    private LinearLayoutManager rvLayoutManager;

    public static FavoritesFragment newInstance() {
        return new FavoritesFragment();
    }

    @Override
    public void onResume() {
        super.onResume();
        presenter.subscribe();
    }

    @Override
    public void onPause() {
        super.onPause();
        presenter.unsubscribe();
    }

    @Override
    public boolean isActive() {
        return isAdded();
    }

    @Override
    public void setPresenter(@NonNull FavoritesContract.Presenter presenter) {
        this.presenter = checkNotNull(presenter);
    }

    @Override
    public void setViewModel(@NonNull FavoritesContract.ViewModel viewModel) {
        this.viewModel = checkNotNull(viewModel);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Nullable
    @Override
    public View onCreateView(
            LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        FragmentLaanoFavoritesBinding binding =
                FragmentLaanoFavoritesBinding.inflate(inflater, container, false);
        viewModel.setInstanceState(savedInstanceState);
        binding.setViewModel((FavoritesViewModel) viewModel);
        // RecyclerView
        setupFavoritesRecyclerView(binding.rvFavorites);
        return binding.getRoot();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.toolbar_favorites, menu);
        MenuItem searchMenuItem = menu.findItem(R.id.toolbar_favorites_search);
        SearchView searchView = (SearchView) MenuItemCompat.getActionView(searchMenuItem);
        MenuItemCompat.setOnActionExpandListener(
                searchMenuItem, new MenuItemCompat.OnActionExpandListener() {

            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {
                return true;
            }

            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                getActivity().supportInvalidateOptionsMenu();
                viewModel.setSearchText(null);
                presenter.loadFavorites(false);
                return true;
            }
        });
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {

            @Override
            public boolean onQueryTextSubmit(String query) {
                viewModel.setSearchText(query);
                presenter.loadFavorites(false);
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                return true;
            }
        });
        if (viewModel.getSearchText() != null) {
            searchMenuItem.expandActionView();
            searchView.setQuery(viewModel.getSearchText(), false);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.toolbar_favorites_filter:
                showFilteringPopupMenu();
                break;
            case R.id.toolbar_favorites_action_mode:
                enableActionMode();
                break;
            default:
                return super.onOptionsItemSelected(item);
        }
        return true;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        viewModel.saveInstanceState(outState);
    }

    @Override
    public void showAddFavorite() {
        Intent intent = new Intent(getContext(), AddEditFavoriteActivity.class);
        startActivityForResult(intent, REQUEST_ADD_FAVORITE);
    }

    @Override
    public void showEditFavorite(@NonNull String favoriteId) {
        Intent intent = new Intent(getContext(), AddEditFavoriteActivity.class);
        intent.putExtra(AddEditFavoriteFragment.ARGUMENT_EDIT_FAVORITE_ID, favoriteId);
        startActivityForResult(intent, REQUEST_EDIT_FAVORITE);
    }

    @Override
    public void showFavorites(@NonNull List<Favorite> favorites) {
        checkNotNull(favorites);

        adapter.swapItems(favorites);
        viewModel.setFavoriteListSize(favorites.size());
        if (viewModel.isActionMode()) {
            enableActionMode();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_ADD_FAVORITE:
                if (resultCode == Activity.RESULT_OK) {
                    adapter.notifyDataSetChanged();
                }
                break;
            case REQUEST_EDIT_FAVORITE:
                if (resultCode == Activity.RESULT_OK) {
                    adapter.notifyDataSetChanged();
                }
                break;
            case REQUEST_FAVORITE_CONFLICT_RESOLUTION:
                adapter.notifyDataSetChanged();
                presenter.updateTabNormalState();
                presenter.loadFavorites(false);
                if (resultCode == FavoritesConflictResolutionDialog.RESULT_OK) {
                    viewModel.showConflictResolutionSuccessfulSnackbar();
                } else if (resultCode == FavoritesConflictResolutionDialog.RESULT_FAILED){
                    viewModel.showConflictResolutionErrorSnackbar();
                }
                break;
            default:
                throw new IllegalStateException("The result received from the unexpected activity");
        }
    }

    private void setupFavoritesRecyclerView(RecyclerView rvFavorites) {
        List<Favorite> favorites = new ArrayList<>(0);
        adapter = new FavoritesAdapter(favorites, presenter, (FavoritesViewModel) viewModel);
        rvFavorites.setAdapter(adapter);
        rvLayoutManager = new LinearLayoutManager(getContext());
        rvFavorites.setLayoutManager(rvLayoutManager);
        DividerItemDecoration dividerItemDecoration = new DividerItemDecoration(
                rvFavorites.getContext(), rvLayoutManager.getOrientation());
        rvFavorites.addItemDecoration(dividerItemDecoration);
    }

    private void showFilteringPopupMenu() {
        PopupMenu popupMenu = new PopupMenu(
                getContext(), getActivity().findViewById(R.id.toolbar_favorites_filter));
        Menu menu = popupMenu.getMenu();
        popupMenu.getMenuInflater().inflate(R.menu.filter, menu);
        popupMenu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case R.id.filter_all:
                    presenter.setFilterType(FilterType.ALL);
                    break;
                case R.id.filter_conflicted:
                    presenter.setFilterType(FilterType.CONFLICTED);
                    break;
            }
            presenter.loadFavorites(false);
            return true;
        });
        MenuItem filterLinkMenuItem = menu.findItem(R.id.filter_link);
        filterLinkMenuItem.setVisible(false);

        MenuItem filterFavoriteMenuItem = menu.findItem(R.id.filter_favorite);
        filterFavoriteMenuItem.setVisible(false);

        MenuItem filterNoteMenuItem = menu.findItem(R.id.filter_note);
        filterNoteMenuItem.setVisible(false);

        MenuItem filterNoTagsMenuItem = menu.findItem(R.id.filter_no_tags);
        filterNoTagsMenuItem.setVisible(false);

        MenuItem filterUnboundMenuItem = menu.findItem(R.id.filter_unbound);
        filterUnboundMenuItem.setVisible(false);
        popupMenu.show();
    }

    @Override
    public void enableActionMode() {
        if (!viewModel.isActionMode()) {
            viewModel.enableActionMode();
        }
        if (actionMode == null) {
            actionMode = ((AppCompatActivity) getActivity()).startSupportActionMode(
                    new FavoritesActionModeCallback());
        }
        updateActionModeTitle();
    }

    @Override
    public void finishActionMode() {
        if (actionMode != null) {
            actionMode.finish(); // NOTE: will call destroyActionMode
        }
    }

    private void destroyActionMode() {
        if (viewModel.isActionMode()) {
            viewModel.disableActionMode();
        }
        if (actionMode != null) {
            actionMode = null;
        }
    }

    @Override
    public void selectionChanged(int position) {
        updateActionModeTitle();
    }

    private void updateActionModeTitle() {
        if (actionMode != null) {
            actionMode.setTitle(getContext().getResources().getString(
                    R.string.laano_favorites_action_mode_selected,
                    viewModel.getSelectedCount(), adapter.getItemCount()));
            if (adapter.getItemCount() <= 0) {
                finishActionMode();
            }
        } // if
    }

    @Override
    public int getPosition(String favoriteId) {
        return adapter.getPosition(favoriteId);
    }

    @Override
    public void scrollToPosition(int position) {
        if (rvLayoutManager != null) {
            rvLayoutManager.scrollToPositionWithOffset(position, 0);
        }
    }

    @Override
    public String removeFavorite(int position) {
        Favorite favorite = adapter.removeItem(position);
        selectionChanged(position);
        viewModel.setFavoriteListSize(adapter.getItemCount());
        return favorite.getId();
    }

    @Override
    public void confirmFavoritesRemoval(int[] selectedIds) {
        FavoriteRemovalConfirmationDialog dialog =
                FavoriteRemovalConfirmationDialog.newInstance(selectedIds);
        dialog.setTargetFragment(this, FavoriteRemovalConfirmationDialog.DIALOG_REQUEST_CODE);
        dialog.show(getFragmentManager(), FavoriteRemovalConfirmationDialog.DIALOG_TAG);
    }

    public void removeFavorites(int[] selectedIds) {
        presenter.deleteFavorites(selectedIds);
    }

    @Override
    public void showConflictResolution(@NonNull String favoriteId) {
        checkNotNull(favoriteId);

        FavoritesConflictResolutionDialog dialog =
                FavoritesConflictResolutionDialog.newInstance(favoriteId);
        dialog.setTargetFragment(this, REQUEST_FAVORITE_CONFLICT_RESOLUTION);
        dialog.show(getFragmentManager(), FavoritesConflictResolutionDialog.DIALOG_TAG);
    }

    public class FavoritesActionModeCallback implements ActionMode.Callback {

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            mode.getMenuInflater().inflate(R.menu.action_mode_favorites, menu);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            menu.findItem(R.id.favorites_delete).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            menu.findItem(R.id.favorites_select_all).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            return true;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            switch (item.getItemId()) {
                case R.id.favorites_delete:
                    presenter.onDeleteClick();
                    break;
                case R.id.favorites_select_all:
                    presenter.onSelectAllClick();
                    updateActionModeTitle();
                    break;
                default:
                    throw new UnsupportedOperationException(
                            "Unknown ActionMode item [" + item.getItemId() + "]");
            }
            return true;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            destroyActionMode();
        }
    }

    public static class FavoriteRemovalConfirmationDialog extends DialogFragment {

        private static final String ARGUMENT_SELECTED_IDS = "SELECTED_IDS";

        public static final String DIALOG_TAG = "FAVORITE_REMOVAL_CONFIRMATION";
        public static final int DIALOG_REQUEST_CODE = 0;

        private int[] selectedIds;

        public static FavoriteRemovalConfirmationDialog newInstance(int[] selectedIds) {
            Bundle args = new Bundle();
            args.putIntArray(ARGUMENT_SELECTED_IDS, selectedIds);
            FavoriteRemovalConfirmationDialog dialog = new FavoriteRemovalConfirmationDialog();
            dialog.setArguments(args);
            return dialog;
        }

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            selectedIds = getArguments().getIntArray(ARGUMENT_SELECTED_IDS);
        }

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            int length = selectedIds.length;
            return new AlertDialog.Builder(getContext())
                    .setTitle(R.string.favorites_delete_confirmation_title)
                    .setMessage(getResources().getQuantityString(
                            R.plurals.favorites_delete_confirmation_message, length, length))
                    .setIcon(R.drawable.ic_warning)
                    .setPositiveButton(R.string.dialog_button_ok, (dialog, which) ->
                            ((FavoritesFragment) getTargetFragment()).removeFavorites(selectedIds))
                    .setNegativeButton(R.string.dialog_button_cancel, null)
                    .create();
        }
    }
}
