/*
 * Copyright (C) 2012 Google Inc.
 * Licensed to The Android Open Source Project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.mail.ui;

import android.app.Activity;
import android.app.ListFragment;
import android.app.LoaderManager;
import android.content.Loader;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListAdapter;
import android.widget.ListView;

import com.android.mail.R;
import com.android.mail.adapter.DrawerItem;
import com.android.mail.content.ObjectCursor;
import com.android.mail.content.ObjectCursorLoader;
import com.android.mail.providers.Account;
import com.android.mail.providers.AccountObserver;
import com.android.mail.providers.AllAccountObserver;
import com.android.mail.providers.Folder;
import com.android.mail.providers.FolderObserver;
import com.android.mail.providers.FolderWatcher;
import com.android.mail.providers.RecentFolderObserver;
import com.android.mail.providers.UIProvider;
import com.android.mail.providers.UIProvider.FolderType;
import com.android.mail.utils.LogTag;
import com.android.mail.utils.LogUtils;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The folder list UI component.
 */
public class FolderListFragment extends ListFragment implements
        LoaderManager.LoaderCallbacks<ObjectCursor<Folder>> {
    private static final String LOG_TAG = LogTag.getLogTag();
    /** The parent activity */
    private ControllableActivity mActivity;
    /** The underlying list view */
    private ListView mListView;
    /** URI that points to the list of folders for the current account. */
    private Uri mFolderListUri;
    /** True if you want a sectioned FolderList, false otherwise. */
    protected boolean mIsSectioned;
    /** An {@link ArrayList} of {@link FolderType}s to exclude from displaying. */
    private ArrayList<Integer> mExcludedFolderTypes;
    /** Object that changes folders on our behalf. */
    private FolderListSelectionListener mFolderChanger;
    /** Object that changes accounts on our behalf */
    private AccountController mAccountChanger;

    /** The currently selected folder (the folder being viewed).  This is never null. */
    private Uri mSelectedFolderUri = Uri.EMPTY;
    /**
     * The current folder from the controller.  This is meant only to check when the unread count
     * goes out of sync and fixing it.
     */
    private Folder mCurrentFolderForUnreadCheck;
    /** Parent of the current folder, or null if the current folder is not a child. */
    private Folder mParentFolder;

    private static final int FOLDER_LOADER_ID = 0;
    /** Key to store {@link #mParentFolder}. */
    private static final String ARG_PARENT_FOLDER = "arg-parent-folder";
    /** Key to store {@link #mIsSectioned} */
    private static final String ARG_IS_SECTIONED = "arg-is-sectioned";
    /** Key to store {@link #mFolderListUri}. */
    private static final String ARG_FOLDER_LIST_URI = "arg-folder-list-uri";
    /** Key to store {@link #mExcludedFolderTypes} */
    private static final String ARG_EXCLUDED_FOLDER_TYPES = "arg-excluded-folder-types";
    /** Key to store {@link #mType} */
    private static final String ARG_TYPE = "arg-flf-type";

    /** Either {@link #TYPE_DRAWER} for drawers or {@link #TYPE_TREE} for hierarchy trees */
    private int mType;
    /** This fragment is a drawer */
    private static final int TYPE_DRAWER = 0;
    /** This fragment is a folder tree */
    private static final int TYPE_TREE = 1;

    private static final String BUNDLE_LIST_STATE = "flf-list-state";
    private static final String BUNDLE_SELECTED_FOLDER = "flf-selected-folder";
    private static final String BUNDLE_SELECTED_TYPE = "flf-selected-type";

    private FolderListFragmentCursorAdapter mCursorAdapter;
    /** Observer to wait for changes to the current folder so we can change the selected folder */
    private FolderObserver mFolderObserver = null;
    /** Listen for account changes. */
    private AccountObserver mAccountObserver = null;

    /** Listen to changes to list of all accounts */
    private AllAccountObserver mAllAccountsObserver = null;
    /**
     * Type of currently selected folder: {@link DrawerItem#FOLDER_SYSTEM},
     * {@link DrawerItem#FOLDER_RECENT} or {@link DrawerItem#FOLDER_USER}.
     */
    // Setting to INERT_HEADER = leaving uninitialized.
    private int mSelectedFolderType = DrawerItem.UNSET;
    private ObjectCursor<Folder> mFutureData;
    private ConversationListCallbacks mConversationListCallback;
    /** The current account according to the controller */
    private Account mCurrentAccount;

    /** List of all accounts currently known */
    private Account[] mAllAccounts;

    /**
     * Constructor needs to be public to handle orientation changes and activity lifecycle events.
     */
    public FolderListFragment() {
        super();
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder(super.toString());
        sb.setLength(sb.length() - 1);
        sb.append(" folder=");
        sb.append(mFolderListUri);
        sb.append(" parent=");
        sb.append(mParentFolder);
        sb.append(" adapterCount=");
        sb.append(mCursorAdapter != null ? mCursorAdapter.getCount() : -1);
        sb.append("}");
        return sb.toString();
    }

    /**
     * Creates a new instance of {@link FolderListFragment}. Gets the current account and current
     * folder through observers.
     */
    public static FolderListFragment ofDrawer() {
        final FolderListFragment fragment = new FolderListFragment();
        // The drawer is always sectioned
        final boolean isSectioned = true;
        fragment.setArguments(getBundleFromArgs(TYPE_DRAWER, null, null, isSectioned, null));
        return fragment;
    }

    /**
     * Creates a new instance of {@link FolderListFragment}, initialized
     * to display the folder and its immediate children.
     * @param folder parent folder whose children are shown
     *
     */
    public static FolderListFragment ofTree(Folder folder) {
        final FolderListFragment fragment = new FolderListFragment();
        // Trees are never sectioned.
        final boolean isSectioned = false;
        fragment.setArguments(getBundleFromArgs(TYPE_TREE, folder, folder.childFoldersListUri,
                isSectioned, null));
        return fragment;
    }

    /**
     * Creates a new instance of {@link FolderListFragment}, initialized
     * to display the folder and its immediate children.
     * @param folderListUri the URI which contains all the list of folders
     * @param excludedFolderTypes A list of {@link FolderType}s to exclude from displaying
     */
    public static FolderListFragment ofTopLevelTree(Uri folderListUri,
            final ArrayList<Integer> excludedFolderTypes) {
        final FolderListFragment fragment = new FolderListFragment();
        // Trees are never sectioned.
        final boolean isSectioned = false;
        fragment.setArguments(getBundleFromArgs(TYPE_TREE, null, folderListUri,
                isSectioned, excludedFolderTypes));
        return fragment;
    }

    /**
     * Construct a bundle that represents the state of this fragment.
     * @param type the type of FLF: {@link #TYPE_DRAWER} or {@link #TYPE_TREE}
     * @param parentFolder non-null for trees, the parent of this list
     * @param isSectioned true if this drawer is sectioned, false otherwise
     * @param folderListUri the URI which contains all the list of folders
     * @param excludedFolderTypes if non-null, this indicates folders to exclude in lists.
     * @return Bundle containing parentFolder, sectioned list boolean and
     *         excluded folder types
     */
    private static Bundle getBundleFromArgs(int type, Folder parentFolder, Uri folderListUri,
            boolean isSectioned, final ArrayList<Integer> excludedFolderTypes) {
        final Bundle args = new Bundle();
        args.putInt(ARG_TYPE, type);
        if (parentFolder != null) {
            args.putParcelable(ARG_PARENT_FOLDER, parentFolder);
        }
        if (folderListUri != null) {
            args.putString(ARG_FOLDER_LIST_URI, folderListUri.toString());
        }
        args.putBoolean(ARG_IS_SECTIONED, isSectioned);
        if (excludedFolderTypes != null) {
            args.putIntegerArrayList(ARG_EXCLUDED_FOLDER_TYPES, excludedFolderTypes);
        }
        return args;
    }

    @Override
    public void onActivityCreated(Bundle savedState) {
        super.onActivityCreated(savedState);
        // Strictly speaking, we get back an android.app.Activity from getActivity. However, the
        // only activity creating a ConversationListContext is a MailActivity which is of type
        // ControllableActivity, so this cast should be safe. If this cast fails, some other
        // activity is creating ConversationListFragments. This activity must be of type
        // ControllableActivity.
        final Activity activity = getActivity();
        Folder currentFolder = null;
        if (! (activity instanceof ControllableActivity)){
            LogUtils.wtf(LOG_TAG, "FolderListFragment expects only a ControllableActivity to" +
                    "create it. Cannot proceed.");
        }
        mActivity = (ControllableActivity) activity;
        mConversationListCallback = mActivity.getListHandler();
        final FolderController controller = mActivity.getFolderController();
        // Listen to folder changes in the future
        mFolderObserver = new FolderObserver() {
            @Override
            public void onChanged(Folder newFolder) {
                setSelectedFolder(newFolder);
            }
        };
        if (controller != null) {
            // Only register for selected folder updates if we have a controller.
            currentFolder = mFolderObserver.initialize(controller);
            mCurrentFolderForUnreadCheck = currentFolder;
        }

        // Initialize adapter for folder/heirarchical list
        final Folder selectedFolder;
        if (mParentFolder != null) {
            mCursorAdapter = new HierarchicalFolderListAdapter(null, mParentFolder);
            selectedFolder = mActivity.getHierarchyFolder();
        } else {
            mCursorAdapter = new FolderListAdapter(mIsSectioned);
            selectedFolder = currentFolder;
        }
        // Is the selected folder fresher than the one we have restored from a bundle?
        if (selectedFolder != null && !selectedFolder.uri.equals(mSelectedFolderUri)) {
            setSelectedFolder(selectedFolder);
        }

        // Assign observers for current account & all accounts
        final AccountController accountController = mActivity.getAccountController();
        mAccountObserver = new AccountObserver() {
            @Override
            public void onChanged(Account newAccount) {
                setSelectedAccount(newAccount);
            }
        };
        if (accountController != null) {
            // Current account and its observer.
            setSelectedAccount(mAccountObserver.initialize(accountController));
            // List of all accounts and its observer.
            mAllAccountsObserver = new AllAccountObserver(){
                @Override
                public void onChanged(Account[] allAccounts) {
                    mAllAccounts = allAccounts;
                }
            };
            mAllAccounts = mAllAccountsObserver.initialize(accountController);
            mAccountChanger = accountController;
        }

        mFolderChanger = mActivity.getFolderListSelectionListener();
        if (mActivity.isFinishing()) {
            // Activity is finishing, just bail.
            return;
        }

        setListAdapter(mCursorAdapter);
    }

    /**
     * Set the instance variables from the arguments provided here.
     * @param args
     */
    private void setInstanceFromBundle(Bundle args) {
        if (args == null) {
            return;
        }
        mParentFolder = (Folder) args.getParcelable(ARG_PARENT_FOLDER);
        final String folderUri = args.getString(ARG_FOLDER_LIST_URI);
        if (folderUri == null) {
            mFolderListUri = Uri.EMPTY;
        } else {
            mFolderListUri = Uri.parse(folderUri);
        }
        mIsSectioned = args.getBoolean(ARG_IS_SECTIONED);
        mExcludedFolderTypes = args.getIntegerArrayList(ARG_EXCLUDED_FOLDER_TYPES);
        mType = args.getInt(ARG_TYPE);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedState) {
        setInstanceFromBundle(getArguments());
        final View rootView = inflater.inflate(R.layout.folder_list, null);
        mListView = (ListView) rootView.findViewById(android.R.id.list);
        mListView.setHeaderDividersEnabled(false);
        mListView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        mListView.setEmptyView(null);
        if (savedState != null && savedState.containsKey(BUNDLE_LIST_STATE)) {
            mListView.onRestoreInstanceState(savedState.getParcelable(BUNDLE_LIST_STATE));
        }
        if (savedState != null && savedState.containsKey(BUNDLE_SELECTED_FOLDER)) {
            mSelectedFolderUri = Uri.parse(savedState.getString(BUNDLE_SELECTED_FOLDER));
            mSelectedFolderType = savedState.getInt(BUNDLE_SELECTED_TYPE);
        } else if (mParentFolder != null) {
            mSelectedFolderUri = mParentFolder.uri;
            // No selected folder type required for hierarchical lists.
        }

        return rootView;
    }

    @Override
    public void onStart() {
        super.onStart();
    }

    @Override
    public void onStop() {
        super.onStop();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mListView != null) {
            outState.putParcelable(BUNDLE_LIST_STATE, mListView.onSaveInstanceState());
        }
        if (mSelectedFolderUri != null) {
            outState.putString(BUNDLE_SELECTED_FOLDER, mSelectedFolderUri.toString());
        }
        outState.putInt(BUNDLE_SELECTED_TYPE, mSelectedFolderType);
    }

    @Override
    public void onDestroyView() {
        if (mCursorAdapter != null) {
            mCursorAdapter.destroy();
        }
        // Clear the adapter.
        setListAdapter(null);
        if (mFolderObserver != null) {
            mFolderObserver.unregisterAndDestroy();
            mFolderObserver = null;
        }
        if (mAccountObserver != null) {
            mAccountObserver.unregisterAndDestroy();
            mAccountObserver = null;
        }
        if (mAllAccountsObserver != null) {
            mAllAccountsObserver.unregisterAndDestroy();
            mAllAccountsObserver = null;
        }
        super.onDestroyView();
    }

    @Override
    public void onListItemClick(ListView l, View v, int position, long id) {
        viewFolderOrChangeAccount(position);
    }

    /**
     * Display the conversation list from the folder at the position given.
     * @param position a zero indexed position into the list.
     */
    private void viewFolderOrChangeAccount(int position) {
        final Object item = getListAdapter().getItem(position);
        final Folder folder;
        if (item instanceof DrawerItem) {
            final DrawerItem folderItem = (DrawerItem) item;
            // Could be a folder, account, or expand block.
            final int itemType = mCursorAdapter.getItemType(folderItem);
            if (itemType == DrawerItem.VIEW_ACCOUNT) {
                // Account, so switch.
                folder = null;
                final Account account = mCursorAdapter.getFullAccount(folderItem);
                mAccountChanger.changeAccount(account);
            } else if (itemType == DrawerItem.VIEW_FOLDER) {
                // Folder type, so change folders only.
                folder = mCursorAdapter.getFullFolder(folderItem);
                mSelectedFolderType = folderItem.mFolderType;
            } else {
                // Do nothing.
                LogUtils.d(LOG_TAG, "FolderListFragment: viewFolderOrChangeAccount():"
                        + " Clicked on unset item in drawer. Offending item is " + item);
                return;
            }
        } else if (item instanceof Folder) {
            folder = (Folder) item;
        } else if (item instanceof ObjectCursor){
            folder = ((ObjectCursor<Folder>) item).getModel();
        } else {
            // Don't know how we got here.
            LogUtils.wtf(LOG_TAG, "viewFolderOrChangeAccount(): invalid item");
            folder = null;
        }
        if (folder != null) {
            // Since we may be looking at hierarchical views, if we can
            // determine the parent of the folder we have tapped, set it here.
            // If we are looking at the folder we are already viewing, don't
            // update its parent!
            folder.parent = folder.equals(mParentFolder) ? null : mParentFolder;
            // Go to the conversation list for this folder.
            mFolderChanger.onFolderSelected(folder);
        }
    }

    @Override
    public Loader<ObjectCursor<Folder>> onCreateLoader(int id, Bundle args) {
        mListView.setEmptyView(null);
        final Uri folderListUri;
        if (mType == TYPE_TREE) {
            // Folder trees, they specify a URI at construction time.
            folderListUri = mFolderListUri;
        } else if (mType == TYPE_DRAWER) {
            // Drawers should have a valid account
            if (mCurrentAccount != null) {
                folderListUri = mCurrentAccount.folderListUri;
            } else {
                LogUtils.wtf(LOG_TAG, "FLF.onCreateLoader() for Drawer with null account");
                return null;
            }
        } else {
            LogUtils.wtf(LOG_TAG, "FLF.onCreateLoader() with weird type");
            return null;
        }
        return new ObjectCursorLoader<Folder>(mActivity.getActivityContext(), folderListUri,
                UIProvider.FOLDERS_PROJECTION, Folder.FACTORY);
    }

    public void onAnimationEnd() {
        if (mFutureData != null) {
            mCursorAdapter.setCursor(mFutureData);
            mFutureData = null;
        }
    }

    @Override
    public void onLoadFinished(Loader<ObjectCursor<Folder>> loader, ObjectCursor<Folder> data) {
        if (mConversationListCallback == null || !mConversationListCallback.isAnimating()) {
            mCursorAdapter.setCursor(data);
        } else {
            mFutureData = data;
            mCursorAdapter.setCursor(null);
        }
    }

    @Override
    public void onLoaderReset(Loader<ObjectCursor<Folder>> loader) {
        mCursorAdapter.setCursor(null);
    }

    /**
     * Interface for all cursor adapters that allow setting a cursor and being destroyed.
     */
    private interface FolderListFragmentCursorAdapter extends ListAdapter {
        /** Update the folder list cursor with the cursor given here. */
        void setCursor(ObjectCursor<Folder> cursor);
        /**
         * Given an item, find the type of the item, which should only be {@link
         * DrawerItem#VIEW_FOLDER} or {@link DrawerItem#VIEW_ACCOUNT}
         * @return item the type of the item.
         */
        int getItemType(DrawerItem item);
        /** Get the folder associated with this item. **/
        Folder getFullFolder(DrawerItem item);
        /** Get the account associated with this item. **/
        Account getFullAccount(DrawerItem item);
        /** Remove all observers and destroy the object. */
        void destroy();
        /** Notifies the adapter that the data has changed. */
        void notifyDataSetChanged();
    }

    /**
     * An adapter for flat folder lists.
     */
    private class FolderListAdapter extends BaseAdapter implements FolderListFragmentCursorAdapter {

        private final RecentFolderObserver mRecentFolderObserver = new RecentFolderObserver() {
            @Override
            public void onChanged() {
                recalculateList();
            }
        };
        /** No resource used for string header in folder list */
        private static final int NO_HEADER_RESOURCE = -1;
        /** Cache of most recently used folders */
        private final RecentFolderList mRecentFolders;
        /** True if the list is sectioned, false otherwise */
        private final boolean mIsSectioned;
        /** All the items */
        private List<DrawerItem> mItemList = new ArrayList<DrawerItem>();
        /** Cursor into the folder list. This might be null. */
        private ObjectCursor<Folder> mCursor = null;
        /** Watcher for tracking and receiving unread counts for mail */
        private FolderWatcher mFolderWatcher = null;

        /** Track whether the accounts have folder watchers added to them yet */
        private boolean mAccountsWatched;

        /**
         * Creates a {@link FolderListAdapter}.This is a flat folder list of all the folders for the
         * given account.
         * @param isSectioned TODO(viki):
         */
        public FolderListAdapter(boolean isSectioned) {
            super();
            mIsSectioned = isSectioned;
            final RecentFolderController controller = mActivity.getRecentFolderController();
            if (controller != null && mIsSectioned) {
                mRecentFolders = mRecentFolderObserver.initialize(controller);
            } else {
                mRecentFolders = null;
            }
            mFolderWatcher = new FolderWatcher(mActivity, this);
            mAccountsWatched = false;
            initFolderWatcher();
        }

        /**
         * If accounts have not yet been added to folder watcher due to various
         * null pointer issues, add them.
         */
        public void initFolderWatcher() {
            if (!mAccountsWatched && mAllAccounts != null) {
                for (final Account account : mAllAccounts) {
                    mFolderWatcher.startWatching(account.settings.defaultInbox);
                }
                mAccountsWatched = true;
            }
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final DrawerItem item = (DrawerItem) getItem(position);
            final View view = item.getView(position, convertView, parent);
            final int type = item.mType;
            if (mListView!= null) {
                final boolean isSelected =
                        item.isHighlighted(mCurrentFolderForUnreadCheck, mSelectedFolderType);
                if (type == DrawerItem.VIEW_FOLDER) {
                    mListView.setItemChecked(position, isSelected);
                }
                // If this is the current folder, also check to verify that the unread count
                // matches what the action bar shows.
                if (type == DrawerItem.VIEW_FOLDER
                        && isSelected
                        && (mCurrentFolderForUnreadCheck != null)
                        && item.mFolder.unreadCount != mCurrentFolderForUnreadCheck.unreadCount) {
                    ((FolderItemView) view).overrideUnreadCount(
                            mCurrentFolderForUnreadCheck.unreadCount);
                }
            }
            return view;
        }

        @Override
        public int getViewTypeCount() {
            // Accounts, headers, folders (all parts of drawer view types)
            return DrawerItem.getViewTypes();
        }

        @Override
        public int getItemViewType(int position) {
            return ((DrawerItem) getItem(position)).mType;
        }

        @Override
        public int getCount() {
            return mItemList.size();
        }

        @Override
        public boolean isEnabled(int position) {
            final DrawerItem item = (DrawerItem) getItem(position);
            return item.isItemEnabled(getCurrentAccountUri());

        }

        private Uri getCurrentAccountUri() {
            return mCurrentAccount == null ? Uri.EMPTY : mCurrentAccount.uri;
        }

        @Override
        public boolean areAllItemsEnabled() {
            // The headers and current accounts are not enabled.
            return false;
        }

        /**
         * Returns all the recent folders from the list given here. Safe to call with a null list.
         * @param recentList a list of all recently accessed folders.
         * @return a valid list of folders, which are all recent folders.
         */
        private List<Folder> getRecentFolders(RecentFolderList recentList) {
            final List<Folder> folderList = new ArrayList<Folder>();
            if (recentList == null) {
                return folderList;
            }
            // Get all recent folders, after removing system folders.
            for (final Folder f : recentList.getRecentFolderList(null)) {
                if (!f.isProviderFolder()) {
                    folderList.add(f);
                }
            }
            return folderList;
        }

        /**
         * Responsible for verifying mCursor, and ensuring any recalculate
         * conditions are met. Also calls notifyDataSetChanged once it's finished
         * populating {@link FolderListAdapter#mItemList}
         */
        private void recalculateList() {
            if (mAllAccountsObserver != null) {
                mAllAccounts = mAllAccountsObserver.getAllAccounts();
            }
            final boolean haveAccount = (mAllAccounts != null && mAllAccounts.length > 0);
            if (!haveAccount) {
                // TODO(viki): How do we get a notification that we have accounts now? Currently
                // we don't, and we should.
                return;
            }
            final List<DrawerItem> newFolderList = new ArrayList<DrawerItem>();
            recalculateListAccounts(newFolderList);
            recalculateListFolders(newFolderList);
            mItemList = newFolderList;
            // Ask the list to invalidate its views.
            notifyDataSetChanged();
        }

        /**
         * Recalculates the accounts if not null and adds them to the list.
         *
         * @param itemList List of drawer items to populate
         */
        private void recalculateListAccounts(List<DrawerItem> itemList) {
            if (mAllAccounts != null) {
                initFolderWatcher();
                // Add all accounts and then the current account
                final Uri currentAccountUri = getCurrentAccountUri();
                for (final Account account : mAllAccounts) {
                    if (!currentAccountUri.equals(account.uri)) {
                        final int unreadCount =
                                mFolderWatcher.getUnreadCount(account.settings.defaultInbox);
                        itemList.add(
                                DrawerItem.ofAccount(mActivity, account, unreadCount, false));
                    }
                }
                final int unreadCount = mFolderWatcher.getUnreadCount(
                        mCurrentAccount.settings.defaultInbox);
                itemList.add(DrawerItem.ofAccount(mActivity, mCurrentAccount, unreadCount, true));
            }
            // TODO(shahrk): Add support for when there's only one account and allAccounts
            // isn't available yet
        }

        /**
         * Recalculates the system, recent and user label lists.
         * This method modifies all the three lists on every single invocation.
         *
         * @param itemList List of drawer items to populate
         */
        private void recalculateListFolders(List<DrawerItem> itemList) {
            // If we are waiting for folder initialization, we don't have any kinds of folders,
            // just the "Waiting for initialization" item.
            if (isCursorInvalid(mCursor)) {
                itemList.add(DrawerItem.forWaitView(mActivity));
                return;
            }

            if (!mIsSectioned) {
                // Adapter for a flat list. Everything is a FOLDER_USER, and there are no headers.
                do {
                    final Folder f = mCursor.getModel();
                    if (!isFolderTypeExcluded(f)) {
                        itemList.add(DrawerItem.ofFolder(mActivity, f, DrawerItem.FOLDER_USER,
                                mCursor.getPosition()));
                    }
                } while (mCursor.moveToNext());
                return;
            }

            // Otherwise, this is an adapter for a sectioned list.
            final List<DrawerItem> allFoldersList = new ArrayList<DrawerItem>();
            final List<DrawerItem> inboxFolders = new ArrayList<DrawerItem>();
            do {
                final Folder f = mCursor.getModel();
                if (!isFolderTypeExcluded(f)) {
                    if (f.isProviderFolder() && f.isInbox()) {
                        inboxFolders.add(DrawerItem.ofFolder(
                                mActivity, f, DrawerItem.FOLDER_SYSTEM, mCursor.getPosition()));
                    } else {
                        allFoldersList.add(DrawerItem.ofFolder(
                                mActivity, f, DrawerItem.FOLDER_USER, mCursor.getPosition()));
                    }
                }
            } while (mCursor.moveToNext());

            // Add all inboxes (sectioned included) before recents.
            addFolderSection(itemList, inboxFolders, NO_HEADER_RESOURCE);

            // Add most recently folders (in alphabetical order) next.
            addRecentsToList(itemList);

            // Add the remaining provider folders followed by all labels.
            addFolderSection(itemList, allFoldersList,  R.string.all_folders_heading);
        }

        /**
         * Given a list of folders as {@link DrawerItem}s, add them to the item
         * list as needed. Passing in a non-0 integer for the resource will
         * enable a header
         *
         * @param destination List of drawer items to populate
         * @param source List of drawer items representing folders to add to the drawer
         * @param headerStringResource
         *            {@link FolderListAdapter#NO_HEADER_RESOURCE} if no header
         *            is required, or res-id otherwise
         */
        private void addFolderSection(List<DrawerItem> destination, List<DrawerItem> source,
                int headerStringResource) {
            if (source.size() > 0) {
                if(headerStringResource != NO_HEADER_RESOURCE) {
                    destination.add(DrawerItem.ofHeader(mActivity, headerStringResource));
                }
                destination.addAll(source);
            }
        }

        /**
         * Add recent folders to the list in order as acquired by the {@link RecentFolderList}.
         *
         * @param destination List of drawer items to populate
         */
        private void addRecentsToList(List<DrawerItem> destination) {
            // If there are recent folders, add them.
            final List<Folder> recentFolderList = getRecentFolders(mRecentFolders);

            // Remove any excluded folder types
            if (mExcludedFolderTypes != null) {
                final Iterator<Folder> iterator = recentFolderList.iterator();
                while (iterator.hasNext()) {
                    if (isFolderTypeExcluded(iterator.next())) {
                        iterator.remove();
                    }
                }
            }

            if (recentFolderList.size() > 0) {
                destination.add(DrawerItem.ofHeader(mActivity, R.string.recent_folders_heading));
                // Recent folders are not queried for position.
                final int position = -1;
                for (Folder f : recentFolderList) {
                    destination.add(DrawerItem.ofFolder(mActivity, f, DrawerItem.FOLDER_RECENT,
                            position));
                }
            }
        }

        /**
         * Check if the cursor provided is valid.
         * @param mCursor
         * @return True if cursor is invalid, false otherwise
         */
        private boolean isCursorInvalid(Cursor mCursor) {
            return mCursor == null || mCursor.isClosed()|| mCursor.getCount() <= 0
                    || !mCursor.moveToFirst();
        }

        @Override
        public void setCursor(ObjectCursor<Folder> cursor) {
            mCursor = cursor;
            recalculateList();
        }

        @Override
        public Object getItem(int position) {
            return mItemList.get(position);
        }

        @Override
        public long getItemId(int position) {
            return getItem(position).hashCode();
        }

        @Override
        public final void destroy() {
            mRecentFolderObserver.unregisterAndDestroy();
        }

        @Override
        public int getItemType(DrawerItem item) {
            return item.mType;
        }

        // TODO(viki): This is strange. We have the full folder and yet we create on from scratch.
        @Override
        public Folder getFullFolder(DrawerItem folderItem) {
            if (folderItem.mFolderType == DrawerItem.FOLDER_RECENT) {
                return folderItem.mFolder;
            } else {
                int pos = folderItem.mPosition;
                if (mFutureData != null) {
                    mCursor = mFutureData;
                    mFutureData = null;
                }
                if (pos > -1 && mCursor != null && !mCursor.isClosed()
                        && mCursor.moveToPosition(folderItem.mPosition)) {
                    return mCursor.getModel();
                } else {
                    return null;
                }
            }
        }

        @Override
        public Account getFullAccount(DrawerItem item) {
            return item.mAccount;
        }
    }

    private class HierarchicalFolderListAdapter extends ArrayAdapter<Folder>
            implements FolderListFragmentCursorAdapter{

        private static final int PARENT = 0;
        private static final int CHILD = 1;
        private final Uri mParentUri;
        private final Folder mParent;
        private final FolderItemView.DropHandler mDropHandler;
        private ObjectCursor<Folder> mCursor;

        public HierarchicalFolderListAdapter(ObjectCursor<Folder> c, Folder parentFolder) {
            super(mActivity.getActivityContext(), R.layout.folder_item);
            mDropHandler = mActivity;
            mParent = parentFolder;
            mParentUri = parentFolder.uri;
            setCursor(c);
        }

        @Override
        public int getViewTypeCount() {
            // Child and Parent
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            final Folder f = getItem(position);
            return f.uri.equals(mParentUri) ? PARENT : CHILD;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final FolderItemView folderItemView;
            final Folder folder = getItem(position);
            boolean isParent = folder.uri.equals(mParentUri);
            if (convertView != null) {
                folderItemView = (FolderItemView) convertView;
            } else {
                int resId = isParent ? R.layout.folder_item : R.layout.child_folder_item;
                folderItemView = (FolderItemView) LayoutInflater.from(
                        mActivity.getActivityContext()).inflate(resId, null);
            }
            folderItemView.bind(folder, mDropHandler);
            if (folder.uri.equals(mSelectedFolderUri)) {
                getListView().setItemChecked(position, true);
                // If this is the current folder, also check to verify that the unread count
                // matches what the action bar shows.
                final boolean unreadCountDiffers = (mCurrentFolderForUnreadCheck != null)
                        && folder.unreadCount != mCurrentFolderForUnreadCheck.unreadCount;
                if (unreadCountDiffers) {
                    folderItemView.overrideUnreadCount(mCurrentFolderForUnreadCheck.unreadCount);
                }
            }
            Folder.setFolderBlockColor(folder, folderItemView.findViewById(R.id.color_block));
            Folder.setIcon(folder, (ImageView) folderItemView.findViewById(R.id.folder_icon));
            return folderItemView;
        }

        @Override
        public void setCursor(ObjectCursor<Folder> cursor) {
            mCursor = cursor;
            clear();
            if (mParent != null) {
                add(mParent);
            }
            if (cursor != null && cursor.getCount() > 0) {
                cursor.moveToFirst();
                do {
                    Folder f = cursor.getModel();
                    f.parent = mParent;
                    add(f);
                } while (cursor.moveToNext());
            }
        }

        @Override
        public void destroy() {
            // Do nothing.
        }

        @Override
        public int getItemType(DrawerItem item) {
            // Always returns folders for now.
            return DrawerItem.VIEW_FOLDER;
        }

        @Override
        public Folder getFullFolder(DrawerItem folderItem) {
            int pos = folderItem.mPosition;
            if (mCursor == null || mCursor.isClosed()) {
                // See if we have a cursor hanging out we can use
                mCursor = mFutureData;
                mFutureData = null;
            }
            if (pos > -1 && mCursor != null && !mCursor.isClosed()
                    && mCursor.moveToPosition(folderItem.mPosition)) {
                return mCursor.getModel();
            } else {
                return null;
            }
        }

        @Override
        public Account getFullAccount(DrawerItem item) {
            return null;
        }
    }

    public Folder getParentFolder() {
        return mParentFolder;
    }

    /**
     * Sets the currently selected folder safely.
     * @param folder
     */
    private void setSelectedFolder(Folder folder) {
        if (folder == null) {
            mSelectedFolderUri = Uri.EMPTY;
            LogUtils.e(LOG_TAG, "FolderListFragment.setSelectedFolder(null) called!");
            return;
        }
        mCurrentFolderForUnreadCheck = folder;
        mSelectedFolderUri = folder.uri;
        setSelectedFolderType(folder);
        if (mCursorAdapter != null) {
            mCursorAdapter.notifyDataSetChanged();
        }
    }

    /**
     * Sets the selected folder type safely.
     * @param folder folder to set to.
     */
    private void setSelectedFolderType(Folder folder) {
        if (mSelectedFolderType == DrawerItem.UNSET) {
            mSelectedFolderType = folder.isProviderFolder() ? DrawerItem.FOLDER_SYSTEM
                    : DrawerItem.FOLDER_USER;
        }
    }

    /**
     * Sets the current account to the one provided here.
     * @param account the current account to set to.
     */
    private void setSelectedAccount(Account account){
        final LoaderManager manager = getLoaderManager();
        if (mCurrentAccount != null && account != null && mCurrentAccount.uri.equals(account.uri)) {
            // If currentAccount is the same as the one we set, restartLoader
            manager.restartLoader(FOLDER_LOADER_ID, Bundle.EMPTY, this);
        } else {
            // Otherwise, recreate the loader entirely by destroying and calling init
            mCurrentAccount = account;
            if (mCurrentAccount != null) {
                manager.destroyLoader(FOLDER_LOADER_ID);
                manager.initLoader(FOLDER_LOADER_ID, Bundle.EMPTY, this);
            }
        }
    }

    public interface FolderListSelectionListener {
        public void onFolderSelected(Folder folder);
    }

    /**
     * Get whether the FolderListFragment is currently showing the hierarchy
     * under a single parent.
     */
    public boolean showingHierarchy() {
        return mParentFolder != null;
    }

    /**
     * Checks if the specified {@link Folder} is a type that we want to exclude from displaying.
     */
    private boolean isFolderTypeExcluded(final Folder folder) {
        if (mExcludedFolderTypes == null) {
            return false;
        }

        for (final int excludedType : mExcludedFolderTypes) {
            if (folder.isType(excludedType)) {
                return true;
            }
        }

        return false;
    }
}
