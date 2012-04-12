/*******************************************************************************
 *      Copyright (C) 2012 Google Inc.
 *      Licensed to The Android Open Source Project.
 *
 *      Licensed under the Apache License, Version 2.0 (the "License");
 *      you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *           http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing, software
 *      distributed under the License is distributed on an "AS IS" BASIS,
 *      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *      See the License for the specific language governing permissions and
 *      limitations under the License.
 *******************************************************************************/

package com.android.mail.ui;

import android.app.ActionBar;
import android.app.ActionBar.LayoutParams;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.FragmentManager;
import android.app.LoaderManager;
import android.app.SearchManager;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.CursorLoader;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.SearchRecentSuggestions;
import android.view.DragEvent;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.widget.Toast;

import com.android.mail.ConversationListContext;
import com.android.mail.R;
import com.android.mail.browse.ConversationCursor;
import com.android.mail.browse.ConversationCursor.ConversationListener;
import com.android.mail.browse.SelectedConversationsActionMenu;
import com.android.mail.compose.ComposeActivity;
import com.android.mail.providers.Account;
import com.android.mail.providers.Conversation;
import com.android.mail.providers.Folder;
import com.android.mail.providers.MailAppProvider;
import com.android.mail.providers.Settings;
import com.android.mail.providers.SuggestionsProvider;
import com.android.mail.providers.UIProvider;
import com.android.mail.providers.UIProvider.AccountCursorExtraKeys;
import com.android.mail.providers.UIProvider.AutoAdvance;
import com.android.mail.providers.UIProvider.ConversationColumns;
import com.android.mail.providers.UIProvider.FolderCapabilities;
import com.android.mail.utils.LogUtils;
import com.android.mail.utils.Utils;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;


/**
 * This is an abstract implementation of the Activity Controller. This class
 * knows how to respond to menu items, state changes, layout changes, etc. It
 * weaves together the views and listeners, dispatching actions to the
 * respective underlying classes.
 * <p>
 * Even though this class is abstract, it should provide default implementations
 * for most, if not all the methods in the ActivityController interface. This
 * makes the task of the subclasses easier: OnePaneActivityController and
 * TwoPaneActivityController can be concise when the common functionality is in
 * AbstractActivityController.
 * </p>
 * <p>
 * In the Gmail codebase, this was called BaseActivityController
 * </p>
 */
public abstract class AbstractActivityController implements ActivityController, ConversationListener {
    // Keys for serialization of various information in Bundles.
    private static final String SAVED_ACCOUNT = "saved-account";
    private static final String SAVED_FOLDER = "saved-folder";
    private static final String SAVED_CONVERSATION = "saved-conversation";
    // Batch conversations stored in the Bundle using this key.
    private static final String SAVED_CONVERSATIONS = "saved-conversations";

    protected static final String WAIT_FRAGMENT_TAG = "wait-fragment";

    /** Are we on a tablet device or not. */
    public final boolean IS_TABLET_DEVICE;

    protected Account mAccount;
    protected Folder mFolder;
    protected ActionBarView mActionBarView;
    protected final RestrictedActivity mActivity;
    protected final Context mContext;
    protected final RecentFolderList mRecentFolderList;
    protected ConversationListContext mConvListContext;
    protected Conversation mCurrentConversation;

    /** A {@link android.content.BroadcastReceiver} that suppresses new e-mail notifications. */
    private SuppressNotificationReceiver mNewEmailReceiver = null;

    protected Handler mHandler = new Handler();
    protected ConversationListFragment mConversationListFragment;
    /**
     * The current mode of the application. All changes in mode are initiated by
     * the activity controller. View mode changes are propagated to classes that
     * attach themselves as listeners of view mode changes.
     */
    protected final ViewMode mViewMode;
    protected ContentResolver mResolver;
    protected FolderListFragment mFolderListFragment;
    protected ConversationViewFragment mConversationViewFragment;
    protected boolean isLoaderInitialized = false;
    private AsyncRefreshTask mAsyncRefreshTask;

    private final Set<Uri> mCurrentAccountUris = Sets.newHashSet();
    protected Settings mCachedSettings;
    protected ConversationCursor mConversationListCursor;
    protected boolean mConversationListenerAdded = false;
    /**
     * Selected conversations, if any.
     */
    private final ConversationSelectionSet mSelectedSet = new ConversationSelectionSet();

    private final int mFolderItemUpdateDelayMs;

    /**
     * Action menu associated with the selected set.
     */
    SelectedConversationsActionMenu mCabActionMenu;
    protected UndoBarView mUndoBarView;

    protected static final String LOG_TAG = new LogUtils().getLogTag();
    /** Constants used to differentiate between the types of loaders. */
    private static final int LOADER_ACCOUNT_CURSOR = 0;
    private static final int LOADER_FOLDER_CURSOR = 2;
    private static final int LOADER_RECENT_FOLDERS = 3;
    private static final int LOADER_CONVERSATION_LIST = 4;
    private static final int LOADER_ACCOUNT_INBOX = 5;
    private static final int LOADER_SEARCH = 6;
    private static final int LOADER_ACCOUNT_UPDATE_CURSOR = 7;


    private static final int ADD_ACCOUNT_REQUEST_CODE = 1;

    public AbstractActivityController(MailActivity activity, ViewMode viewMode) {
        mActivity = activity;
        mViewMode = viewMode;
        mContext = activity.getApplicationContext();
        IS_TABLET_DEVICE = Utils.useTabletUI(mContext);
        mRecentFolderList = new RecentFolderList(mContext, this);
        // Allow the fragment to observe changes to its own selection set. No other object is
        // aware of the selected set.
        mSelectedSet.addObserver(this);

        mFolderItemUpdateDelayMs =
                mContext.getResources().getInteger(R.integer.folder_item_refresh_delay_ms);
    }

    @Override
    public synchronized void attachConversationList(ConversationListFragment fragment) {
        // If there is an existing fragment, unregister it
        if (mConversationListFragment != null) {
            mViewMode.removeListener(mConversationListFragment);
        }
        mConversationListFragment = fragment;
        // If the current fragment is non-null, add it as a listener.
        if (fragment != null) {
            mViewMode.addListener(mConversationListFragment);
        }
    }

    @Override
    public synchronized void attachFolderList(FolderListFragment fragment) {
        // If there is an existing fragment, unregister it
        if (mFolderListFragment != null) {
            mViewMode.removeListener(mFolderListFragment);
        }
        mFolderListFragment = fragment;
        if (fragment != null) {
            mViewMode.addListener(mFolderListFragment);
        }
    }

    @Override
    public void attachConversationView(ConversationViewFragment conversationViewFragment) {
        mConversationViewFragment = conversationViewFragment;
    }

    @Override
    public void clearSubject() {
        // TODO(viki): Auto-generated method stub
    }

    @Override
    public Account getCurrentAccount() {
        return mAccount;
    }

    @Override
    public ConversationListContext getCurrentListContext() {
        return mConvListContext;
    }

    @Override
    public String getHelpContext() {
        return "Mail";
    }

    @Override
    public int getMode() {
        return mViewMode.getMode();
    }

    @Override
    public String getUnshownSubject(String subject) {
        // Calculate how much of the subject is shown, and return the remaining.
        return null;
    }

    @Override
    public void handleConversationLoadError() {
        // TODO(viki): Auto-generated method stub
    }

    @Override
    public ConversationCursor getConversationListCursor() {
        return mConversationListCursor;
    }

    @Override
    public void initConversationListCursor() {
        mActivity.getLoaderManager().restartLoader(LOADER_CONVERSATION_LIST, Bundle.EMPTY,
                new LoaderManager.LoaderCallbacks<ConversationCursor>() {

                    @Override
                    public void onLoadFinished(Loader<ConversationCursor> loader,
                            ConversationCursor data) {
                        mConversationListCursor = data;
                        if (mConversationListCursor.isRefreshReady()) {
                            mConversationListCursor.sync();
                            refreshAdapter();
                        }
                        if (mConversationListFragment != null) {
                            mConversationListFragment.onCursorUpdated();
                            if (!mConversationListenerAdded) {
                                // TODO(mindyp): when we move to the cursor loader, we need
                                // to add/remove the listener when we create/ destroy loaders.
                                mConversationListCursor
                                        .addListener(AbstractActivityController.this);
                                mConversationListenerAdded = true;
                            }
                        }
                        if (shouldShowFirstConversation()) {
                            if (mConversationListCursor.getCount() > 0) {
                                mConversationListCursor.moveToPosition(0);
                                mConversationListFragment.getListView().setItemChecked(0, true);
                                Conversation conv = new Conversation(mConversationListCursor);
                                conv.position = 0;
                                onConversationSelected(conv);
                            }
                        }

                    }

                    @Override
                    public void onLoaderReset(Loader<ConversationCursor> loader) {
                        if (mConversationListFragment == null) {
                            return;
                        }
                        mConversationListFragment.onCursorUpdated();
                    }

                    @Override
                    public Loader<ConversationCursor> onCreateLoader(int id, Bundle args) {
                        return new ConversationCursorLoader((Activity) mActivity, mAccount,
                                UIProvider.CONVERSATION_PROJECTION, mFolder.conversationListUri);
                    }

                });
    }

    /**
     * Initialize the action bar. This is not visible to OnePaneController and
     * TwoPaneController so they cannot override this behavior.
     */
    private void initCustomActionBarView() {
        ActionBar actionBar = mActivity.getActionBar();
        mActionBarView = (ActionBarView) LayoutInflater.from(mContext).inflate(
                R.layout.actionbar_view, null);
        if (actionBar != null && mActionBarView != null) {
            // Why have a different variable for the same thing? We should apply
            // the same actions
            // on mActionBarView instead.
            mActionBarView.initialize(mActivity, this, mViewMode, actionBar, mRecentFolderList);
            actionBar.setCustomView(mActionBarView, new ActionBar.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            actionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM,
                    ActionBar.DISPLAY_SHOW_CUSTOM | ActionBar.DISPLAY_SHOW_TITLE);
        }
    }

    /**
     * Returns whether the conversation list fragment is visible or not.
     * Different layouts will have their own notion on the visibility of
     * fragments, so this method needs to be overriden.
     *
     * @return
     */
    protected abstract boolean isConversationListVisible();

    @Override
    public void onAccountChanged(Account account) {
        if (!account.equals(mAccount)) {
            // Current account is different from the new account, restart loaders and show
            // the account Inbox.
            mAccount = account;
            mFolder = null;
            onSettingsChanged(mAccount.settings);
            mActionBarView.setAccount(mAccount);
            loadAccountInbox();

            mRecentFolderList.setCurrentAccount(account);
            restartOptionalLoader(LOADER_RECENT_FOLDERS);
            mActivity.invalidateOptionsMenu();

            disableNotificationsOnAccountChange(mAccount);

            restartOptionalLoader(LOADER_ACCOUNT_UPDATE_CURSOR);

            MailAppProvider.getInstance().setLastViewedAccount(mAccount.uri.toString());
        } else {
            // Current account is the same as the new account. Load the default inbox if the
            // current inbox is not the same as the default inbox.
            final Uri oldUri = mFolder != null ? mFolder.uri : Uri.EMPTY;
            final Uri newUri = getDefaultInboxUri(mCachedSettings);
            if ((mFolder == null || mFolder.type == UIProvider.FolderType.INBOX)
                    && !oldUri.equals(newUri)) {
                loadAccountInbox();
            }
        }
    }

    /**
     * Returns the URI of the current account's default inbox if available, otherwise
     * returns the empty URI {@link Uri#EMPTY}
     * @return
     */
    private Uri getDefaultInboxUri(Settings settings) {
        if (settings != null && settings.defaultInbox != null) {
            return settings.defaultInbox;
        }
        return Uri.EMPTY;
    }

    public void onSettingsChanged(Settings settings) {
        final Uri oldUri = getDefaultInboxUri(mCachedSettings);
        final Uri newUri = getDefaultInboxUri(settings);
        mCachedSettings = settings;
        resetActionBarIcon();
        // Only restart the loader if the defaultInboxUri is not the same as
        // the folder we are already loading.
        final boolean changed = !oldUri.equals(newUri);
        if (settings != null
                && settings.defaultInbox != null
                && (mFolder == null
                // we really only want CHANGES to the inbox setting, not just
                // the first setting of it.
                || (mFolder.type == UIProvider.FolderType.INBOX && !oldUri.equals(Uri.EMPTY))
                && changed)) {
            loadAccountInbox();
        }
    }

    @Override
    public Settings getSettings() {
        return mCachedSettings;
    }

    private void fetchSearchFolder(Intent intent) {
        Bundle args = new Bundle();
        args.putString(ConversationListContext.EXTRA_SEARCH_QUERY, intent
                .getStringExtra(ConversationListContext.EXTRA_SEARCH_QUERY));
        mActivity.getLoaderManager().restartLoader(LOADER_SEARCH, args, this);
    }

    @Override
    public void onFolderChanged(Folder folder) {
        if (folder != null && !folder.equals(mFolder)) {
            setFolder(folder);
            mConvListContext = ConversationListContext.forFolder(mContext, mAccount, mFolder);
            showConversationList(mConvListContext);

            // Add the folder that we were viewing to the recent folders list.
            // TODO: this may need to be fine tuned.  If this is the signal that is indicating that
            // the list is shown to the user, this could fire in one pane if the user goes directly
            // to a conversation
            updateRecentFolderList();
        }
    }

    /**
     * Update the recent folders. This only needs to be done once when accessing a new folder.
     */
    private void updateRecentFolderList() {
        if (mFolder != null) {
            mRecentFolderList.touchFolder(mFolder, mAccount);
        }
    }

    // TODO(mindyp): set this up to store a copy of the folder as a transient
    // field in the account.
    protected void loadAccountInbox() {
        restartOptionalLoader(LOADER_ACCOUNT_INBOX);
    }

    /** Set the current folder */
    private void setFolder(Folder folder) {
        // Start watching folder for sync status.
        if (folder != null && !folder.equals(mFolder)) {
            mActionBarView.setRefreshInProgress(false);
            mFolder = folder;
            mActionBarView.setFolder(mFolder);
            mActivity.getLoaderManager().restartLoader(LOADER_FOLDER_CURSOR, null, this);
            initConversationListCursor();
        } else if (folder == null) {
            LogUtils.wtf(LOG_TAG, "Folder in setFolder is null");
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == ADD_ACCOUNT_REQUEST_CODE) {
            // We were waiting for the user to create an account
            if (resultCode == Activity.RESULT_OK) {
                // restart the loader to get the updated list of accounts
                mActivity.getLoaderManager().initLoader(
                        LOADER_ACCOUNT_CURSOR, null, this);
            } else {
                // The user failed to create an account, just exit the app
                mActivity.finish();
            }
        }
    }

    @Override
    public void onConversationListVisibilityChanged(boolean visible) {
        // TODO(viki): Auto-generated method stub
    }

    /**
     * By default, doing nothing is right. A two-pane controller will need to
     * override this.
     */
    @Override
    public void onConversationVisibilityChanged(boolean visible) {
        // Do nothing.
        return;
    }

    @Override
    public boolean onCreate(Bundle savedState) {
        // Initialize the action bar view.
        initCustomActionBarView();
        // Allow shortcut keys to function for the ActionBar and menus.
        mActivity.setDefaultKeyMode(Activity.DEFAULT_KEYS_SHORTCUT);
        mResolver = mActivity.getContentResolver();

        mNewEmailReceiver = new SuppressNotificationReceiver();

        // All the individual UI components listen for ViewMode changes. This
        // simplifies the amount of logic in the AbstractActivityController, but increases the
        // possibility of timing-related bugs.
        mViewMode.addListener(this);
        assert (mActionBarView != null);
        mViewMode.addListener(mActionBarView);

        restoreState(savedState);
        mUndoBarView = (UndoBarView) mActivity.findViewById(R.id.undo_view);
        return true;
    }

    @Override
    public Dialog onCreateDialog(int id, Bundle bundle) {
        return null;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = mActivity.getMenuInflater();
        inflater.inflate(mActionBarView.getOptionsMenuId(), menu);
        mActionBarView.onCreateOptionsMenu(menu);
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // TODO(viki): Auto-generated method stub
        return false;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int id = item.getItemId();
        boolean handled = true;
        switch (id) {
            case android.R.id.home:
                onUpPressed();
                break;
            case R.id.compose:
                ComposeActivity.compose(mActivity.getActivityContext(), mAccount);
                break;
            case R.id.show_all_folders:
                showFolderList();
                break;
            case R.id.refresh:
                requestFolderRefresh();
                break;
            case R.id.settings:
                Utils.showSettings(mActivity.getActivityContext(), mAccount);
                break;
            case R.id.folder_options:
                Utils.showFolderSettings(mActivity.getActivityContext(), mAccount, mFolder);
                break;
            case R.id.help_info_menu_item:
                // TODO: enable context sensitive help
                Utils.showHelp(mActivity.getActivityContext(), mAccount, null);
                break;
            case R.id.feedback_menu_item:
                Utils.sendFeedback(mActivity.getActivityContext(), mAccount);
                break;
            case R.id.manage_folders_item:
                Utils.showManageFolder(mActivity.getActivityContext(), mAccount);
                break;
            default:
                handled = false;
                break;
        }
        return handled;
    }

    /**
     * Return the auto advance setting for the current account.
     * @param activity
     * @return the autoadvance setting, a constant from {@link AutoAdvance}
     */
    static int getAutoAdvanceSetting(RestrictedActivity activity) {
        final Settings settings = activity.getSettings();
        // TODO(mindyp): if this isn't set, then show the dialog telling the user to set it.
        // Remove defaulting to AutoAdvance.LIST.
        final int autoAdvance = (settings != null) ?
                (settings.autoAdvance == AutoAdvance.UNSET ?
                        AutoAdvance.LIST : settings.autoAdvance)
                : AutoAdvance.LIST;
        return autoAdvance;
    }

    /**
     * Implements folder changes. This class is a listener because folder changes need to be
     * performed <b>after</b> the ConversationListFragment has finished animating away the
     * removal of the conversation.
     *
     */
    protected abstract class FolderChangeListener implements ActionCompleteListener {
        protected final String mFolderChangeList;
        protected final boolean mDestructiveChange;

        public FolderChangeListener(String changeList, boolean destructive) {
            mFolderChangeList = changeList;
            mDestructiveChange = destructive;
        }

        @Override
        public abstract void onActionComplete();
    }

    /**
     * Update the specified column name in conversation for a boolean value.
     * @param columnName
     * @param value
     */
    protected void updateCurrentConversation(String columnName, boolean value) {
        Conversation.updateBoolean(mContext, ImmutableList.of(mCurrentConversation), columnName,
                value);
        if (mConversationListFragment != null) {
            mConversationListFragment.requestListRefresh();
        }
    }

    /**
     * Update the specified column name in conversation for an integer value.
     * @param columnName
     * @param value
     */
    protected void updateCurrentConversation(String columnName, int value) {
        Conversation.updateInt(mContext, ImmutableList.of(mCurrentConversation), columnName, value);
        if (mConversationListFragment != null) {
            mConversationListFragment.requestListRefresh();
        }
    }

    protected void updateCurrentConversation(String columnName, String value) {
        Conversation.updateString(mContext, ImmutableList.of(mCurrentConversation), columnName,
                value);
        if (mConversationListFragment != null) {
            mConversationListFragment.requestListRefresh();
        }
    }

    private void requestFolderRefresh() {
        if (mFolder != null) {
            if (mAsyncRefreshTask != null) {
                mAsyncRefreshTask.cancel(true);
            }
            mAsyncRefreshTask = new AsyncRefreshTask(mContext, mFolder);
            mAsyncRefreshTask.execute();
        }
    }

    /**
     * Confirm (based on user's settings) and delete a conversation from the conversation list and
     * from the database.
     * @param showDialog
     * @param confirmResource
     * @param listener
     */
    protected void confirmAndDelete(boolean showDialog, int confirmResource,
            final ActionCompleteListener listener) {
        final ArrayList<Conversation> single = new ArrayList<Conversation>();
        single.add(mCurrentConversation);
        if (showDialog) {
            final AlertDialog.OnClickListener onClick = new AlertDialog.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    requestDelete(listener);
                }
            };
            final CharSequence message = Utils.formatPlural(mContext, confirmResource, 1);
            new AlertDialog.Builder(mActivity.getActivityContext()).setMessage(message)
                    .setPositiveButton(R.string.ok, onClick)
                    .setNegativeButton(R.string.cancel, null)
                    .create().show();
        } else {
            requestDelete(listener);
        }
    }


    protected abstract void requestDelete(ActionCompleteListener listener);


    @Override
    public void onPrepareDialog(int id, Dialog dialog, Bundle bundle) {
        // TODO(viki): Auto-generated method stub

    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        mActionBarView.onPrepareOptionsMenu(menu);
        return true;
    }

    @Override
    public void onPause() {
        isLoaderInitialized = false;

        enableNotifications();
    }

    @Override
    public void onResume() {
        // Register the receiver that will prevent the status receiver from
        // displaying its notification icon as long as we're running.
        // The SupressNotificationReceiver will block the broadcast if we're looking at the folder
        // that the notification was received for.
        disableNotifications();

        if (mActionBarView != null) {
            mActionBarView.onResume();
        }

    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        if (mAccount != null) {
            LogUtils.d(LOG_TAG, "Saving the account now");
            outState.putParcelable(SAVED_ACCOUNT, mAccount);
        }
        if (mFolder != null) {
            outState.putParcelable(SAVED_FOLDER, mFolder);
        }
        if (mCurrentConversation != null && mViewMode.getMode() == ViewMode.CONVERSATION) {
            outState.putParcelable(SAVED_CONVERSATION, mCurrentConversation);
        }
        if (!mSelectedSet.isEmpty()) {
            outState.putParcelable(SAVED_CONVERSATIONS, mSelectedSet);
        }
    }

    @Override
    public void onSearchRequested(String query) {
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_SEARCH);
        intent.putExtra(ConversationListContext.EXTRA_SEARCH_QUERY, query);
        intent.putExtra(Utils.EXTRA_ACCOUNT, mAccount);
        intent.setComponent(mActivity.getComponentName());
        mActionBarView.collapseSearch();
        mActivity.startActivity(intent);
    }

    @Override
    public void onStartDragMode() {
        // TODO(viki): Auto-generated method stub
    }

    @Override
    public void onStop() {
        // TODO(viki): Auto-generated method stub
    }

    @Override
    public void onStopDragMode() {
        // TODO(viki): Auto-generated method stub
    }

    /**
     * {@inheritDoc} Subclasses must override this to listen to mode changes
     * from the ViewMode. Subclasses <b>must</b> call the parent's
     * onViewModeChanged since the parent will handle common state changes.
     */
    @Override
    public void onViewModeChanged(int newMode) {
        // Perform any mode specific work here.
        // reset the action bar icon based on the mode. Why don't the individual
        // controllers do
        // this themselves?

        // In conversation list mode, clean up the conversation.
        if (newMode == ViewMode.CONVERSATION_LIST) {
            // Clean up the conversation here.
        }

        // We don't want to invalidate the options menu when switching to
        // conversation
        // mode, as it will happen when the conversation finishes loading.
        if (newMode != ViewMode.CONVERSATION) {
            mActivity.invalidateOptionsMenu();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        // TODO(viki): Auto-generated method stub
    }

    /**
     * Restore the state from the previous bundle. Subclasses should call this
     * method from the parent class, since it performs important UI
     * initialization.
     *
     * @param savedState
     */
    protected void restoreState(Bundle savedState) {
        final Intent intent = mActivity.getIntent();
        boolean handled = false;
        if (savedState != null) {
            if (savedState.containsKey(SAVED_ACCOUNT)) {
                final Account account = ((Account) savedState.getParcelable(SAVED_ACCOUNT));
                onAccountChanged(account);
            }
            if (savedState.containsKey(SAVED_FOLDER)) {
                // Open the folder.
                onFolderChanged((Folder) savedState.getParcelable(SAVED_FOLDER));
                handled = true;
            }
            if (savedState.containsKey(SAVED_CONVERSATION)) {
                // Open the conversation.
                setCurrentConversation((Conversation) savedState.getParcelable(SAVED_CONVERSATION));
                showConversation(mCurrentConversation);
                handled = true;
            }
        } else if (intent != null) {
            if (Intent.ACTION_VIEW.equals(intent.getAction())) {
                if (intent.hasExtra(Utils.EXTRA_ACCOUNT)) {
                    onAccountChanged(((Account) intent.getParcelableExtra(Utils.EXTRA_ACCOUNT)));
                } else if (intent.hasExtra(Utils.EXTRA_ACCOUNT_STRING)) {
                    onAccountChanged(Account.newinstance(intent
                            .getStringExtra(Utils.EXTRA_ACCOUNT_STRING)));
                }

                Folder folder = null;
                if (intent.hasExtra(Utils.EXTRA_FOLDER)) {
                    // Open the folder.
                    LogUtils.d(LOG_TAG, "SHOW THE FOLDER at %s",
                            intent.getParcelableExtra(Utils.EXTRA_FOLDER));
                    folder = (Folder) intent.getParcelableExtra(Utils.EXTRA_FOLDER);

                } else if (intent.hasExtra(Utils.EXTRA_FOLDER_STRING)) {
                    // Open the folder.
                    folder = new Folder(intent.getStringExtra(Utils.EXTRA_FOLDER_STRING));
                }
                if (folder != null) {
                    onFolderChanged(folder);
                    handled = true;
                }

                if (intent.hasExtra(Utils.EXTRA_CONVERSATION)) {
                    // Open the conversation.
                    LogUtils.d(LOG_TAG, "SHOW THE CONVERSATION at %s",
                            intent.getParcelableExtra(Utils.EXTRA_CONVERSATION));
                    setCurrentConversation((Conversation) intent
                            .getParcelableExtra(Utils.EXTRA_CONVERSATION));
                    showConversation(mCurrentConversation);
                    handled = true;
                }

                if (!handled) {
                    // Nothing was saved; just load the account inbox.
                    loadAccountInbox();
                }
            } else if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
                // Save this search query for future suggestions.
                final String query = intent.getStringExtra(SearchManager.QUERY);
                final String authority = mContext.getString(R.string.suggestions_authority);
                SearchRecentSuggestions suggestions = new SearchRecentSuggestions(
                        mContext, authority, SuggestionsProvider.MODE);
                suggestions.saveRecentQuery(query, null);

                mViewMode.enterSearchResultsListMode();
                mAccount = (((Account) intent.getParcelableExtra(Utils.EXTRA_ACCOUNT)));
                mActionBarView.setAccount(mAccount);
                mActivity.invalidateOptionsMenu();
                restartOptionalLoader(LOADER_RECENT_FOLDERS);
                mRecentFolderList.setCurrentAccount(mAccount);
                fetchSearchFolder(intent);
            }
            if (mAccount != null) {
                restartOptionalLoader(LOADER_ACCOUNT_UPDATE_CURSOR);
            }
        }

        /**
         * Restore the state of selected conversations. This needs to be done after the correct mode
         * is set and the action bar is fully initialized. If not, several key pieces of state
         * information will be missing, and the split views may not be initialized correctly.
         * @param savedState
         */
        restoreSelectedConversations(savedState);
        // Create the accounts loader; this loads the account switch spinner.
        mActivity.getLoaderManager().initLoader(LOADER_ACCOUNT_CURSOR, null, this);
    }

    /**
     * Copy any selected conversations stored in the saved bundle into our selection set,
     * triggering {@link ConversationSetObserver} callbacks as our selection set changes.
     *
     */
    private void restoreSelectedConversations(Bundle savedState) {
        if (savedState == null) {
            mSelectedSet.clear();
            return;
        }
        final ConversationSelectionSet selectedSet = savedState.getParcelable(SAVED_CONVERSATIONS);
        if (selectedSet == null || selectedSet.isEmpty()) {
            mSelectedSet.clear();
            return;
        }

        // putAll will take care of calling our registered onSetPopulated method
        // FIXME: disabled until we correctly handle selection set bringup when list fragment
        // does not yet exist (b/6268401)
        //mSelectedSet.putAll(selectedSet);
    }

    @Override
    public void setSubject(String subject) {
        // Do something useful with the subject. This requires changing the
        // conversation view's subject text.
    }

    /**
     * Children can override this method, but they must call super.showConversation().
     * {@inheritDoc}
     */
    @Override
    public void showConversation(Conversation conversation) {
        // Set the current conversation just in case it wasn't already set.
        setCurrentConversation(conversation);
    }

    /**
     * Children can override this method, but they must call super.showWaitForInitialization().
     * {@inheritDoc}
     */
    @Override
    public void showWaitForInitialization() {
        mViewMode.enterWaitingForInitializationMode();
    }

    @Override
    public void hideWaitForInitialization() {
    }

    @Override
    public void updateWaitMode() {
        final FragmentManager manager = mActivity.getFragmentManager();
        final WaitFragment waitFragment =
                (WaitFragment)manager.findFragmentByTag(WAIT_FRAGMENT_TAG);
        if (waitFragment != null) {
            waitFragment.updateAccount(mAccount);
        }
    }

    @Override
    public boolean inWaitMode() {
        final FragmentManager manager = mActivity.getFragmentManager();
        final WaitFragment waitFragment =
                (WaitFragment)manager.findFragmentByTag(WAIT_FRAGMENT_TAG);
        if (waitFragment != null) {
            final Account fragmentAccount = waitFragment.getAccount();
            return fragmentAccount.uri.equals(mAccount.uri) &&
                    mViewMode.getMode() == ViewMode.WAITING_FOR_ACCOUNT_INITIALIZATION;
        }
        return false;
    }

    /**
     * Children can override this method, but they must call super.showConversationList().
     * {@inheritDoc}
     */
    @Override
    public void showConversationList(ConversationListContext listContext) {
    }

    @Override
    public void onConversationSelected(Conversation conversation) {
        showConversation(conversation);
        if (mConvListContext != null && mConvListContext.isSearchResult()) {
            mViewMode.enterSearchResultsConversationMode();
        } else {
            mViewMode.enterConversationMode();
        }
    }

    /**
     * Set the current conversation. This is the conversation on which all actions are performed.
     * Do not modify mCurrentConversation except through this method, which makes it easy to
     * perform common actions associated with changing the current conversation.
     * @param conversation
     */
    protected void setCurrentConversation(Conversation conversation) {
        mCurrentConversation = conversation;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        // Create a loader to listen in on account changes.
        switch (id) {
            case LOADER_ACCOUNT_CURSOR:
                return new CursorLoader(mContext, MailAppProvider.getAccountsUri(),
                        UIProvider.ACCOUNTS_PROJECTION, null, null, null);
            case LOADER_FOLDER_CURSOR:
                final CursorLoader loader = new CursorLoader(mContext, mFolder.uri,
                        UIProvider.FOLDERS_PROJECTION, null, null, null);
                loader.setUpdateThrottle(mFolderItemUpdateDelayMs);
                return loader;
            case LOADER_RECENT_FOLDERS:
                if (mAccount.recentFolderListUri != null) {
                    return new CursorLoader(mContext, mAccount.recentFolderListUri,
                            UIProvider.FOLDERS_PROJECTION, null, null, null);
                }
                break;
            case LOADER_ACCOUNT_INBOX:
                Settings settings = getSettings();
                final Uri inboxUri;
                if (settings != null) {
                    inboxUri = settings.defaultInbox;
                } else {
                    inboxUri = mAccount.folderListUri;
                }
                if (inboxUri != null) {
                    return new CursorLoader(mContext, inboxUri, UIProvider.FOLDERS_PROJECTION, null,
                            null, null);
                }
                break;
            case LOADER_SEARCH:
                return Folder.forSearchResults(mAccount,
                        args.getString(ConversationListContext.EXTRA_SEARCH_QUERY),
                        mActivity.getActivityContext());
            case LOADER_ACCOUNT_UPDATE_CURSOR:
                return new CursorLoader(mContext, mAccount.uri, UIProvider.ACCOUNTS_PROJECTION,
                        null, null, null);
            default:
                LogUtils.wtf(LOG_TAG, "Loader returned unexpected id: %d", id);
        }
        return null;
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {

    }

    /**
     * {@link LoaderManager} currently has a bug in
     * {@link LoaderManager#restartLoader(int, Bundle, android.app.LoaderManager.LoaderCallbacks)}
     * where, if a previous onCreateLoader returned a null loader, this method will NPE. Work around
     * this bug by destroying any loaders that may have been created as null (essentially because
     * they are optional loads, and may not apply to a particular account).
     * <p>
     * A simple null check before restarting a loader will not work, because that would not
     * give the controller a chance to invalidate UI corresponding the prior loader result.
     *
     * @param id loader ID to safely restart
     */
    private void restartOptionalLoader(int id) {
        final LoaderManager lm = mActivity.getLoaderManager();
        lm.destroyLoader(id);
        lm.restartLoader(id, Bundle.EMPTY, this);
    }

    /**
     * Start a loader with the given id. This should be called when we know that the previous
     * state of the application matches this state, and we are happy if we get the previously
     * created loader with this id. If that is not true, consider calling
     * {@link #restartOptionalLoader(int)} instead.
     * @param id
     */
    private void startLoader(int id) {
        final LoaderManager lm = mActivity.getLoaderManager();
        lm.initLoader(id, Bundle.EMPTY, this);
    }

    private boolean accountsUpdated(Cursor accountCursor) {
        // Check to see if the current account hasn't been set, or the account cursor is empty
        if (mAccount == null || !accountCursor.moveToFirst()) {
            return true;
        }

        // Check to see if the number of accounts are different, from the number we saw on the last
        // updated
        if (mCurrentAccountUris.size() != accountCursor.getCount()) {
            return true;
        }

        // Check to see if the account list is different or if the current account is not found in
        // the cursor.
        boolean foundCurrentAccount = false;
        do {
            final Uri accountUri =
                    Uri.parse(accountCursor.getString(UIProvider.ACCOUNT_URI_COLUMN));
            if (!foundCurrentAccount && mAccount.uri.equals(accountUri)) {
                foundCurrentAccount = true;
            }

            if (!mCurrentAccountUris.contains(accountUri)) {
                return true;
            }
        } while (accountCursor.moveToNext());

        // As long as we found the current account, the list hasn't been updated
        return !foundCurrentAccount;
    }

    /**
     * Update the accounts on the device. This currently loads the first account
     * in the list.
     *
     * @param loader
     * @param accounts cursor into the AccountCache
     * @return true if the update was successful, false otherwise
     */
    private boolean updateAccounts(Loader<Cursor> loader, Cursor accounts) {
        if (accounts == null || !accounts.moveToFirst()) {
            return false;
        }

        final Account[] allAccounts = Account.getAllAccounts(accounts);

        // Save the uris for the accounts
        mCurrentAccountUris.clear();
        for (Account account : allAccounts) {
            mCurrentAccountUris.add(account.uri);
        }

        // 1. current account is already set and is in allAccounts -> no-op
        // 2. current account is set and is not in allAccounts -> pick first (acct was deleted?)
        // 3. saved pref has an account -> pick that one
        // 4. otherwise just pick first

        Account newAccount = null;

        if (mAccount != null) {
            if (!mCurrentAccountUris.contains(mAccount.uri)) {
                newAccount = allAccounts[0];
            } else {
                newAccount = mAccount;
            }
        } else {
            final String lastAccountUri = MailAppProvider.getInstance()
                    .getLastViewedAccount();
            if (lastAccountUri != null) {
                for (int i = 0; i < allAccounts.length; i++) {
                    final Account acct = allAccounts[i];
                    if (lastAccountUri.equals(acct.uri.toString())) {
                        newAccount = acct;
                        break;
                    }
                }
            }
            if (newAccount == null) {
                newAccount = allAccounts[0];
            }
        }

        onAccountChanged(newAccount);

        mActionBarView.setAccounts(allAccounts);
        return (allAccounts.length > 0);
    }

    private void disableNotifications() {
        mNewEmailReceiver.activate(mContext, this);
    }

    private void enableNotifications() {
        mNewEmailReceiver.deactivate();
    }

    private void disableNotificationsOnAccountChange(Account account) {
        // If the new mail suppression receiver is activated for a different account, we want to
        // activate it for the new account.
        if (mNewEmailReceiver.activated() &&
                !mNewEmailReceiver.notificationsDisabledForAccount(account)) {
            // Deactivate the current receiver, otherwise multiple receivers may be registered.
            mNewEmailReceiver.deactivate();
            mNewEmailReceiver.activate(mContext, this);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        // We want to reinitialize only if we haven't ever been initialized, or
        // if the current account has vanished.
        if (data == null) {
            LogUtils.e(LOG_TAG, "Received null cursor from loader id: %d", loader.getId());
        }
        switch (loader.getId()) {
            case LOADER_ACCOUNT_CURSOR:
                // If the account list is not not null, and the account list cursor is empty,
                // we need to start the specified activity.
                if (data != null && data.getCount() == 0) {
                    // If an empty cursor is returned, the MailAppProvider is indicating that
                    // no accounts have been specified.  We want to navigate to the "add account"
                    // activity that will handle the intent returned by the MailAppProvider

                    // If the MailAppProvider believes that all accounts have been loaded, and the
                    // account list is still empty, we want to prompt the user to add an account
                    final Bundle extras = data.getExtras();
                    final boolean accountsLoaded =
                            extras.getInt(AccountCursorExtraKeys.ACCOUNTS_LOADED) != 0;

                    if (accountsLoaded) {
                        final Intent noAccountIntent = MailAppProvider.getNoAccountIntent(mContext);
                        if (noAccountIntent != null) {
                            mActivity.startActivityForResult(noAccountIntent,
                                    ADD_ACCOUNT_REQUEST_CODE);
                        }
                    }
                } else {
                    final boolean accountListUpdated = accountsUpdated(data);
                    if (!isLoaderInitialized || accountListUpdated) {
                        isLoaderInitialized = updateAccounts(loader, data);
                    }
                }
                break;
            case LOADER_ACCOUNT_UPDATE_CURSOR:
                // We have gotten an update for current account.

                // Make sure that this is an update for what is the current account
                if (data != null && data.moveToFirst()) {
                    final Account updatedAccount = new Account(data);

                    if (updatedAccount.uri.equals(mAccount.uri)) {
                        // Update the controller's reference to the current acccount
                        mAccount = updatedAccount;
                        mCachedSettings = mAccount.settings;

                        // Got an update for the current account
                        final boolean inWaitingMode = inWaitMode();
                        if (!updatedAccount.isAccountIntialized() && !inWaitingMode) {
                            // Transition to waiting mode
                            showWaitForInitialization();
                        } else if (updatedAccount.isAccountIntialized() && inWaitingMode) {
                            // Dismiss waiting mode
                            hideWaitForInitialization();
                        } else if (!updatedAccount.isAccountIntialized() && inWaitingMode) {
                            // Update the WaitFragment's account object
                            updateWaitMode();
                        }
                    } else {
                        LogUtils.e(LOG_TAG, "Got update for account: %s with current account: %s",
                                updatedAccount.uri, mAccount.uri);
                        // We need to restart the loader, so the correct account information will
                        // be returned
                        restartOptionalLoader(LOADER_ACCOUNT_UPDATE_CURSOR);
                    }
                }
                break;
            case LOADER_FOLDER_CURSOR:
                // Check status of the cursor.
                data.moveToFirst();
                Folder folder = new Folder(data);
                if (folder.isSyncInProgress()) {
                    mActionBarView.onRefreshStarted();
                } else {
                    // Stop the spinner here.
                    mActionBarView.onRefreshStopped(folder.lastSyncResult);
                }
                mActionBarView.onFolderUpdated(folder);
                if (mConversationListFragment != null) {
                    mConversationListFragment.onFolderUpdated(folder);
                }
                LogUtils.v(LOG_TAG, "FOLDER STATUS = %d", folder.syncStatus);
                break;
            case LOADER_RECENT_FOLDERS:
                mRecentFolderList.loadFromUiProvider(data);
                break;
            case LOADER_ACCOUNT_INBOX:
                if (data.moveToFirst() && !data.isClosed()) {
                    Folder inbox = new Folder(data);
                    onFolderChanged(inbox);
                    // Just want to get the inbox, don't care about updates to it
                    // as this will be tracked by the folder change listener.
                    mActivity.getLoaderManager().destroyLoader(LOADER_ACCOUNT_INBOX);
                } else {
                    LogUtils.d(LOG_TAG, "Unable to get the account inbox for account %s",
                            mAccount != null ? mAccount.name : "");
                }
                break;
            case LOADER_SEARCH:
                data.moveToFirst();
                Folder search = new Folder(data);
                setFolder(search);
                mConvListContext = ConversationListContext.forSearchQuery(mAccount, mFolder,
                        mActivity.getIntent()
                                .getStringExtra(UIProvider.SearchQueryParameters.QUERY));
                showConversationList(mConvListContext);
                mActivity.invalidateOptionsMenu();
                mActivity.getLoaderManager().destroyLoader(LOADER_SEARCH);
                break;
        }
    }

    protected abstract class DestructiveActionListener implements ActionCompleteListener {
        protected final int mAction;

        /**
         * Create a listener object. action is one of four constants: R.id.y_button (archive),
         * R.id.delete , R.id.mute, and R.id.report_spam.
         * @param action
         */
        public DestructiveActionListener(int action) {
            mAction = action;
        }

        public void performConversationAction(Collection<Conversation> single) {
            switch (mAction) {
                case R.id.archive:
                    LogUtils.d(LOG_TAG, "Archiving conversation %s", mCurrentConversation);
                    Conversation.archive(mContext, single);
                    break;
                case R.id.delete:
                    LogUtils.d(LOG_TAG, "Deleting conversation %s", mCurrentConversation);
                    Conversation.delete(mContext, single);
                    break;
                case R.id.mute:
                    LogUtils.d(LOG_TAG, "Muting conversation %s", mCurrentConversation);
                    if (mFolder.supportsCapability(FolderCapabilities.DESTRUCTIVE_MUTE))
                        mCurrentConversation.localDeleteOnUpdate = true;
                    Conversation.mute(mContext, single);
                    break;
                case R.id.report_spam:
                    LogUtils.d(LOG_TAG, "reporting spam conversation %s", mCurrentConversation);
                    Conversation.reportSpam(mContext, single);
                    break;
            }
        }

        public Conversation getNextConversation() {
            Conversation next = null;
            int pref = getAutoAdvanceSetting(mActivity);
            Cursor c = mConversationListCursor;
            if (c != null) {
                c.moveToPosition(mCurrentConversation.position);
            }
            switch (pref) {
                case AutoAdvance.NEWER:
                    if (c.moveToPrevious()) {
                        next = new Conversation(c);
                    }
                    break;
                case AutoAdvance.OLDER:
                    if (c.moveToNext()) {
                        next = new Conversation(c);
                    }
                    break;
            }
            return next;
        }

        @Override
        public abstract void onActionComplete();
    }

    // Called from the FolderSelectionDialog after a user is done changing
    // folders.
    @Override
    public void onFolderChangesCommit(ArrayList<Folder> folderChangeList) {
        // Get currently active folder info and compare it to the list
        // these conversations have been given; if they no longer contain
        // the selected folder, delete them from the list.
        HashSet<String> folderUris = new HashSet<String>();
        if (folderChangeList != null && !folderChangeList.isEmpty()) {
            for (Folder f : folderChangeList) {
                folderUris.add(f.uri.toString());
            }
        }
        final boolean destructiveChange = !folderUris.contains(mFolder.uri.toString());
        DestructiveActionListener listener = getFolderDestructiveActionListener();
        StringBuilder foldersUrisString = new StringBuilder();
        boolean first = true;
        for (Folder f : folderChangeList) {
            if (first) {
                first = false;
            } else {
                foldersUrisString.append(',');
            }
            foldersUrisString.append(f.uri.toString());
        }
        updateCurrentConversation(ConversationColumns.FOLDER_LIST, foldersUrisString.toString());
        updateCurrentConversation(ConversationColumns.RAW_FOLDERS,
                Folder.getSerializedFolderString(mFolder, folderChangeList));
        // TODO: (mindyp): set ConversationColumns.RAW_FOLDERS like in
        // SelectedConversationsActionMenu
        if (destructiveChange) {
            mCurrentConversation.localDeleteOnUpdate = true;
            requestDelete(listener);
        } else if (mConversationListFragment != null) {
            mConversationListFragment.requestListRefresh();
        }
    }

    protected abstract DestructiveActionListener getFolderDestructiveActionListener();

    @Override
    public void onRefreshRequired() {
        // Refresh the query in the background
        getConversationListCursor().refresh();
    }

    @Override
    public void onRefreshReady() {
        ArrayList<Integer> deletedRows = mConversationListCursor.getRefreshDeletions();
        // If we have any deletions from the server, and the conversations are in the list view,
        // remove them from a selected set, if any
        if (!deletedRows.isEmpty() && !mSelectedSet.isEmpty()) {
            mSelectedSet.delete(deletedRows);
        }
        // If we have any deletions from the server, animate them away
        if (!deletedRows.isEmpty() && mConversationListFragment != null) {
            AnimatedAdapter adapter = mConversationListFragment.getAnimatedAdapter();
            if (adapter != null) {
                mConversationListFragment.getAnimatedAdapter().delete(deletedRows,
                       this);
            }
        } else {
            // Swap cursors
            getConversationListCursor().sync();
            refreshAdapter();
        }
    }

    @Override
    public void onDataSetChanged() {
        refreshAdapter();
    }

    private void refreshAdapter() {
        if (mConversationListFragment != null) {
            AnimatedAdapter adapter = mConversationListFragment.getAnimatedAdapter();
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        }
    }

    @Override
    public void onSetEmpty() {
    }

    @Override
    public void onSetPopulated(ConversationSelectionSet set) {
        mCabActionMenu = new SelectedConversationsActionMenu(mActivity, set,
                mConversationListFragment.getAnimatedAdapter(), this, this,
                mAccount, mFolder, (SwipeableListView) mConversationListFragment.getListView());
        enableCabMode();
    }

    @Override
    public void onSetChanged(ConversationSelectionSet set) {
        // Do nothing. We don't care about changes to the set.
    }

    @Override
    public ConversationSelectionSet getSelectedSet() {
        return mSelectedSet;
    }

    /**
     * Disable the Contextual Action Bar (CAB). The selected set is not changed.
     */
    protected void disableCabMode() {
        if (mCabActionMenu != null) {
            mCabActionMenu.deactivate();
        }
    }

    /**
     * Re-enable the CAB menu if required. The selection set is not changed.
     */
    protected void enableCabMode() {
        if (mCabActionMenu != null) {
            mCabActionMenu.activate();
        }
    }

    @Override
    public void onActionComplete() {
        if (getConversationListCursor().isRefreshReady()) {
            refreshAdapter();
        }
    }

    @Override
    public void startSearch() {
        if (mAccount.supportsCapability(UIProvider.AccountCapabilities.LOCAL_SEARCH)
                | mAccount.supportsCapability(UIProvider.AccountCapabilities.SERVER_SEARCH)) {
            onSearchRequested(mActionBarView.getQuery());
        } else {
            Toast.makeText(mActivity.getActivityContext(), mActivity.getActivityContext()
                    .getString(R.string.search_unsupported), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void exitSearchMode() {
        if (mViewMode.getMode() == ViewMode.SEARCH_RESULTS_LIST) {
            mActivity.finish();
        }
    }

    /**
     * Supports dragging conversations to a folder.
     */
    @Override
    public boolean supportsDrag(DragEvent event, Folder folder) {
        return (folder != null
                && event != null
                && event.getClipDescription() != null
                && folder.supportsCapability
                    (UIProvider.FolderCapabilities.CAN_ACCEPT_MOVED_MESSAGES)
                && folder.supportsCapability
                    (UIProvider.FolderCapabilities.CAN_HOLD_MAIL)
                && !mFolder.uri.equals(folder.uri));
    }

    /**
     * Handles dropping conversations to a label.
     */
    @Override
    public void handleDrop(DragEvent event, final Folder folder) {
        /*
         * Expect clip data has form: [conversations_uri, conversationId1,
         * maxMessageId1, label1, conversationId2, maxMessageId2, label2, ...]
         */
        if (!supportsDrag(event, folder)) {
            return;
        }
        ClipData data = event.getClipData();
        ArrayList<Integer> conversationPositions = Lists.newArrayList();
        for (int i = 1; i < data.getItemCount(); i += 3) {
            int position = Integer.parseInt(data.getItemAt(i).getText().toString());
            conversationPositions.add(position);
        }
        final Collection<Conversation> conversations = mSelectedSet.values();
        mConversationListFragment.requestDelete(conversations,
                new ActionCompleteListener() {
                    @Override
                    public void onActionComplete() {
                        AbstractActivityController.this.onActionComplete();
                        ArrayList<Folder> changes = new ArrayList<Folder>();
                        changes.add(folder);
                        Conversation.updateString(mContext, conversations,
                                ConversationColumns.FOLDER_LIST, folder.uri.toString());
                        Conversation.updateString(mContext, conversations,
                                ConversationColumns.RAW_FOLDERS,
                                Folder.getSerializedFolderString(mFolder, changes));
                        onUndoAvailable(new UndoOperation(conversations
                                .size(), R.id.change_folder));
                        mSelectedSet.clear();
                    }
                });
    }

    @Override
    public void onUndoCancel() {
        mUndoBarView.hide(false);
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            if (mUndoBarView != null && !mUndoBarView.isEventInUndo(event)) {
                mUndoBarView.hide(true);
            }
        }
    }
}
