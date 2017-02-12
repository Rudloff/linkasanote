package com.bytesforge.linkasanote.addeditaccount.nextcloud;

import android.accounts.Account;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.bytesforge.linkasanote.BasePresenter;
import com.bytesforge.linkasanote.BaseView;
import com.bytesforge.linkasanote.sync.operations.OperationsService;
import com.bytesforge.linkasanote.sync.operations.nextcloud.GetServerInfoOperation;
import com.owncloud.android.lib.common.accounts.AccountUtils;
import com.owncloud.android.lib.common.operations.RemoteOperationResult;

public interface NextcloudContract {

    interface View extends BaseView<Presenter> {

        boolean isActive();

        Account[] getAccountsWithPermissionCheck();
        boolean addAccount(@NonNull Account account, @NonNull String password, @NonNull Bundle data);
        void updateAccount(@NonNull Account account, @NonNull String password, @NonNull Bundle data)
                throws AccountUtils.AccountNotFoundException;
        void finishActivity(@NonNull Account account, @NonNull String password, @NonNull Bundle data);
        void cancelActivity();
        Bundle getAccountState(@NonNull Account account);
        void showAccountDoesNotExistSnackbar();
    }

    interface ViewModel extends BaseView<Presenter> {

        void loadInstanceState(@NonNull Bundle outState);
        void applyInstanceState(@NonNull Bundle state);
        void validateServer();

        void showRefreshButton();
        void hideRefreshButton();
        void enableLoginButton();
        void disableLoginButton();
        void checkLoginButton();

        void showEmptyUrlWarning();
        void showMalformedUrlWarning();
        void showTestingConnectionStatus();
        void showTestingAuthStatus();
        void showCheckUrlWaitingForServiceStatus();
        void showCheckAuthWaitingForServiceStatus();
        void showConnectionResultStatus(RemoteOperationResult.ResultCode result);
        void showAuthResultStatus(RemoteOperationResult.ResultCode result);
        void showGetAccountsPermissionDeniedWarning();
        void showSomethingWrongSnackbar();
    }

    interface Presenter extends BasePresenter {

        boolean isNewAccount();
        void populateAccount();

        void setViewModel(@NonNull NextcloudContract.ViewModel viewModel);
        void setOperationsService(OperationsService service);
        Bundle getInstanceState();
        void applyInstanceState(@Nullable Bundle state);

        String normalizeUrl(String url);
        void checkUrl(String url);
        void checkAuth(String username, String password);
        boolean isServerUrlValid();
        void setServerInfo(GetServerInfoOperation.ServerInfo serverInfo);
    }
}
