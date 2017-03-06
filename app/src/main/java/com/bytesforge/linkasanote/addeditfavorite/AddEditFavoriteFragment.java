package com.bytesforge.linkasanote.addeditfavorite;

import android.app.Activity;
import android.databinding.DataBindingUtil;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.text.InputFilter;
import android.text.SpannableStringBuilder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;

import com.bytesforge.linkasanote.R;
import com.bytesforge.linkasanote.data.Favorite;
import com.bytesforge.linkasanote.data.Tag;
import com.bytesforge.linkasanote.databinding.FragmentAddEditFavoriteBinding;
import com.bytesforge.linkasanote.utils.CommonUtils;
import com.tokenautocomplete.FilteredArrayAdapter;
import com.tokenautocomplete.TokenCompleteTextView;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;

public class AddEditFavoriteFragment extends Fragment implements AddEditFavoriteContract.View {

    public static final String ARGUMENT_EDIT_FAVORITE_ID = "EDIT_FAVORITE_ID";

    private AddEditFavoriteContract.Presenter presenter;
    private AddEditFavoriteContract.ViewModel viewModel;
    private FragmentAddEditFavoriteBinding binding;

    private List<Tag> tags;

    public static AddEditFavoriteFragment newInstance() {
        return new AddEditFavoriteFragment();
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
    public void setPresenter(@NonNull AddEditFavoriteContract.Presenter presenter) {
        this.presenter = checkNotNull(presenter);
    }

    @Override
    public void setViewModel(@NonNull AddEditFavoriteContract.ViewModel viewModel) {
        this.viewModel = checkNotNull(viewModel);
    }

    @Override
    public void finishActivity() {
        getActivity().setResult(Activity.RESULT_OK);
        getActivity().finish();
    }

    @Nullable
    @Override
    public View onCreateView(
            LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = DataBindingUtil.inflate(
                inflater, R.layout.fragment_add_edit_favorite, container, false);
        viewModel.setInstanceState(savedInstanceState);
        binding.setViewModel((AddEditFavoriteViewModel) viewModel);
        if (savedInstanceState == null && !presenter.isNewFavorite()) {
            presenter.populateFavorite();
        }
        // FavoriteTags
        final FavoriteTagsCompletionView completionView = binding.favoriteTags;
        if (completionView != null) {
            setupTagsCompletionView(completionView);
            viewModel.setTagsCompletionView(completionView);
        }
        return binding.getRoot();
    }

    @Override
    public void setupFavoriteState(@NonNull Favorite favorite) {
        checkNotNull(favorite);

        Bundle state = getFavoriteState(favorite);
        viewModel.applyInstanceState(state);
        viewModel.setFavoriteTags(favorite.getTags());
    }

    private Bundle getFavoriteState(@NonNull Favorite favorite) {
        checkNotNull(favorite);

        Bundle state = viewModel.getDefaultInstanceState();
        state.putString(AddEditFavoriteViewModel.STATE_FAVORITE_NAME, favorite.getName());

        return state;
    }

    private void setupTagsCompletionView(FavoriteTagsCompletionView completionView) {
        // Options
        completionView.setTokenClickStyle(TokenCompleteTextView.TokenClickStyle.Select);
        completionView.setDeletionStyle(TokenCompleteTextView.TokenDeleteStyle.SelectThenDelete);
        completionView.allowCollapse(false);
        char[] splitChars = {' '};
        completionView.setSplitChar(splitChars);
        completionView.allowDuplicates(false);
        completionView.performBestGuess(false);
        int threshold = getContext().getResources().getInteger(R.integer.tags_autocomplete_threshold);
        completionView.setThreshold(threshold);
        completionView.setTokenListener((AddEditFavoritePresenter) presenter);
        // Adapter
        tags = new ArrayList<>();
        ArrayAdapter<Tag> adapter = new FilteredArrayAdapter<Tag>(
                getContext(), android.R.layout.simple_list_item_1, tags) {
            @Override
            protected boolean keepObject(Tag tag, String mask) {
                String name = tag.getName();
                return name != null && name.toLowerCase().startsWith(mask)
                        && !completionView.getObjects().contains(tag);
            }
        };
        completionView.setAdapter(adapter);
        // Input filter
        InputFilter alphanumericFilter = (source, start, end, dest, dStart, dEnd) -> {
            if (source instanceof SpannableStringBuilder) {
                binding.favoriteTagsLayout.setError(null);
                return source;
            } else {
                StringBuilder filteredStringBuilder = new StringBuilder();
                for (int i = start; i < end; i++) {
                    char currentChar = source.charAt(i);
                    if (Character.isLetterOrDigit(currentChar)) {
                        filteredStringBuilder.append(currentChar);
                        binding.favoriteTagsLayout.setError(null);
                    } else {
                        binding.favoriteTagsLayout.setError(getResources().getString(
                                R.string.add_edit_favorite_tags_validation_error));
                    }
                }
                return filteredStringBuilder.toString();
            }
        };
        InputFilter[] inputFilters = CommonUtils.arrayAdd(
                completionView.getFilters(), alphanumericFilter);
        completionView.setFilters(inputFilters);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        viewModel.saveInstanceState(outState);
    }

    @Override
    public void swapTagsCompletionViewItems(List<Tag> tags) {
        if (this.tags != null) {
            this.tags.clear();
            this.tags.addAll(tags);
        }
    }
}