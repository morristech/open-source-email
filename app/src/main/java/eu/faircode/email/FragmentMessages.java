package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    NetGuard is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with NetGuard.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018 by Marcel Bokhorst (M66B)
*/

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
import androidx.constraintlayout.widget.Group;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.paging.LivePagedListBuilder;
import androidx.paging.PagedList;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class FragmentMessages extends FragmentEx {
    private ViewGroup view;
    private TextView tvSupport;
    private ImageButton ibHintSupport;
    private ImageButton ibHintActions;
    private RecyclerView rvMessage;
    private TextView tvNoEmail;
    private ProgressBar pbWait;
    private Group grpSupport;
    private Group grpHintSupport;
    private Group grpHintActions;
    private Group grpReady;
    private FloatingActionButton fab;
    private FloatingActionButton fabPrev;
    private FloatingActionButton fabNext;

    private long folder = -1;
    private long account = -1;
    private String thread = null;
    private String search = null;

    private long primary = -1;
    private boolean connected = false;
    private AdapterMessage adapter;

    private AdapterMessage.ViewType viewType;
    private LiveData<PagedList<TupleMessageEx>> messages = null;

    private boolean autoExpand = true;
    private List<Long> expanded = new ArrayList<>();
    private List<Long> headers = new ArrayList<>();
    private List<Long> images = new ArrayList<>();

    private BoundaryCallbackMessages searchCallback = null;

    private ExecutorService executor = Executors.newCachedThreadPool(Helper.backgroundThreadFactory);

    private static final int LOCAL_PAGE_SIZE = 50;
    private static final int REMOTE_PAGE_SIZE = 10;
    private static final int UNDO_TIMEOUT = 5000; // milliseconds

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Get arguments
        Bundle args = getArguments();
        account = args.getLong("account", -1);
        folder = args.getLong("folder", -1);
        thread = args.getString("thread");
        search = args.getString("search");

        if (TextUtils.isEmpty(search))
            if (thread == null)
                if (folder < 0)
                    viewType = AdapterMessage.ViewType.UNIFIED;
                else
                    viewType = AdapterMessage.ViewType.FOLDER;
            else
                viewType = AdapterMessage.ViewType.THREAD;
        else
            viewType = AdapterMessage.ViewType.SEARCH;
    }

    @Override
    @Nullable
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        view = (ViewGroup) inflater.inflate(R.layout.fragment_messages, container, false);

        setHasOptionsMenu(true);

        // Get controls
        tvSupport = view.findViewById(R.id.tvSupport);
        ibHintSupport = view.findViewById(R.id.ibHintSupport);
        ibHintActions = view.findViewById(R.id.ibHintActions);
        rvMessage = view.findViewById(R.id.rvFolder);
        tvNoEmail = view.findViewById(R.id.tvNoEmail);
        pbWait = view.findViewById(R.id.pbWait);
        grpSupport = view.findViewById(R.id.grpSupport);
        grpHintSupport = view.findViewById(R.id.grpHintSupport);
        grpHintActions = view.findViewById(R.id.grpHintActions);
        grpReady = view.findViewById(R.id.grpReady);
        fab = view.findViewById(R.id.fab);
        fabPrev = view.findViewById(R.id.fabPrev);
        fabNext = view.findViewById(R.id.fabNext);

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());

        // Wire controls

        tvSupport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FragmentTransaction fragmentTransaction = getFragmentManager().beginTransaction();
                fragmentTransaction.replace(R.id.content_frame, new FragmentPro()).addToBackStack("pro");
                fragmentTransaction.commit();
            }
        });

        ibHintActions.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.edit().putBoolean("message_actions", true).apply();
                grpHintActions.setVisibility(View.GONE);
            }
        });

        ibHintSupport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.edit().putBoolean("app_support", true).apply();
                grpHintSupport.setVisibility(View.GONE);
            }
        });

        rvMessage.setHasFixedSize(false);
        LinearLayoutManager llm = new LinearLayoutManager(getContext());
        rvMessage.setLayoutManager(llm);

        adapter = new AdapterMessage(getContext(), getViewLifecycleOwner(), viewType, new AdapterMessage.IProperties() {
            @Override
            public void setExpanded(long id, boolean expand) {
                if (expand) {
                    expanded.add(id);
                    handleExpand(id);
                } else
                    expanded.remove(id);
            }

            @Override
            public void setHeaders(long id, boolean show) {
                if (show)
                    headers.add(id);
                else
                    headers.remove(id);
            }

            @Override
            public void setImages(long id, boolean show) {
                if (show)
                    images.add(id);
                else
                    images.remove(id);
            }

            @Override
            public boolean isExpanded(long id) {
                return expanded.contains(id);
            }

            @Override
            public boolean showHeaders(long id) {
                return headers.contains(id);
            }

            @Override
            public boolean showImages(long id) {
                return images.contains(id);
            }
        });
        rvMessage.setAdapter(adapter);

        new ItemTouchHelper(new ItemTouchHelper.Callback() {
            @Override
            public int getMovementFlags(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
                if (!prefs.getBoolean("swipe", true))
                    return 0;

                int pos = viewHolder.getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION)
                    return 0;

                TupleMessageEx message = ((AdapterMessage) rvMessage.getAdapter()).getCurrentList().get(pos);
                if (message == null ||
                        expanded.contains(message.id) ||
                        EntityFolder.OUTBOX.equals(message.folderType))
                    return 0;

                return makeMovementFlags(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT);
            }

            @Override
            public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder, RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onChildDraw(Canvas canvas, RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder, float dX, float dY, int actionState, boolean isCurrentlyActive) {
                int pos = viewHolder.getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION)
                    return;

                TupleMessageEx message = ((AdapterMessage) rvMessage.getAdapter()).getCurrentList().get(pos);
                if (message == null)
                    return;

                boolean inbox = (EntityFolder.ARCHIVE.equals(message.folderType) || EntityFolder.TRASH.equals(message.folderType));

                View itemView = viewHolder.itemView;
                int margin = Math.round(12 * (getResources().getDisplayMetrics().density));

                if (dX > margin) {
                    // Right swipe
                    Drawable d = getResources().getDrawable(inbox ? R.drawable.baseline_move_to_inbox_24 : R.drawable.baseline_archive_24, getContext().getTheme());
                    int padding = (itemView.getHeight() - d.getIntrinsicHeight());
                    d.setBounds(
                            itemView.getLeft() + margin,
                            itemView.getTop() + padding / 2,
                            itemView.getLeft() + margin + d.getIntrinsicWidth(),
                            itemView.getTop() + padding / 2 + d.getIntrinsicHeight());
                    d.draw(canvas);
                } else if (dX < -margin) {
                    // Left swipe
                    Drawable d = getResources().getDrawable(inbox ? R.drawable.baseline_move_to_inbox_24 : R.drawable.baseline_delete_24, getContext().getTheme());
                    int padding = (itemView.getHeight() - d.getIntrinsicHeight());
                    d.setBounds(
                            itemView.getLeft() + itemView.getWidth() - d.getIntrinsicWidth() - margin,
                            itemView.getTop() + padding / 2,
                            itemView.getLeft() + itemView.getWidth() - margin,
                            itemView.getTop() + padding / 2 + d.getIntrinsicHeight());
                    d.draw(canvas);
                }

                super.onChildDraw(canvas, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
            }

            @Override
            public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
                int pos = viewHolder.getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION)
                    return;

                TupleMessageEx message = ((AdapterMessage) rvMessage.getAdapter()).getCurrentList().get(pos);
                if (message == null)
                    return;
                Log.i(Helper.TAG, "Swiped dir=" + direction + " message=" + message.id);

                Bundle args = new Bundle();
                args.putLong("id", message.id);
                args.putBoolean("thread", viewType != AdapterMessage.ViewType.THREAD);
                args.putInt("direction", direction);

                new SimpleTask<MessageTarget>() {
                    @Override
                    protected MessageTarget onLoad(Context context, Bundle args) {
                        long id = args.getLong("id");
                        boolean thread = args.getBoolean("thread");
                        int direction = args.getInt("direction");

                        MessageTarget result = new MessageTarget();
                        EntityFolder target = null;

                        // Get target folder and hide message
                        DB db = DB.getInstance(context);
                        try {
                            db.beginTransaction();

                            EntityMessage message = db.message().getMessage(id);
                            EntityFolder folder = db.folder().getFolder(message.folder);

                            if (EntityFolder.ARCHIVE.equals(folder.type) || EntityFolder.TRASH.equals(folder.type))
                                target = db.folder().getFolderByType(message.account, EntityFolder.INBOX);
                            else {
                                if (direction == ItemTouchHelper.RIGHT)
                                    target = db.folder().getFolderByType(message.account, EntityFolder.ARCHIVE);
                                if (direction == ItemTouchHelper.LEFT || target == null)
                                    target = db.folder().getFolderByType(message.account, EntityFolder.TRASH);
                                if (target == null)
                                    target = db.folder().getFolderByType(message.account, EntityFolder.INBOX);
                            }

                            result.target = target.name;
                            result.display = (target.display == null ? target.name : target.display);

                            if (thread) {
                                List<EntityMessage> messages =
                                        db.message().getMessageByThread(message.account, message.thread);
                                for (EntityMessage threaded : messages) {
                                    if (!threaded.ui_hide && threaded.folder.equals(message.folder))
                                        result.ids.add(threaded.id);
                                }
                            } else
                                result.ids.add(message.id);

                            for (long mid : result.ids) {
                                Log.i(Helper.TAG, "Move hide id=" + mid + " target=" + result.target);
                                db.message().setMessageUiHide(mid, true);
                            }

                            db.setTransactionSuccessful();
                        } finally {
                            db.endTransaction();
                        }

                        return result;
                    }

                    @Override
                    protected void onLoaded(final Bundle args, final MessageTarget result) {
                        // Show undo snackbar
                        final Snackbar snackbar = Snackbar.make(
                                view,
                                getString(R.string.title_moving, Helper.localizeFolderName(getContext(), result.display)),
                                Snackbar.LENGTH_INDEFINITE);
                        snackbar.setAction(R.string.title_undo, new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                snackbar.dismiss();

                                Bundle args = new Bundle();
                                args.putSerializable("result", result);

                                // Show message again
                                new SimpleTask<Void>() {
                                    @Override
                                    protected Void onLoad(Context context, Bundle args) {
                                        MessageTarget result = (MessageTarget) args.getSerializable("result");
                                        for (long id : result.ids) {
                                            Log.i(Helper.TAG, "Move undo id=" + id);
                                            DB.getInstance(context).message().setMessageUiHide(id, false);
                                        }
                                        return null;
                                    }

                                    @Override
                                    protected void onException(Bundle args, Throwable ex) {
                                        super.onException(args, ex);
                                    }
                                }.load(FragmentMessages.this, args);
                            }
                        });
                        snackbar.show();

                        // Wait
                        new Handler().postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                Log.i(Helper.TAG, "Move timeout");

                                // Remove snackbar
                                if (snackbar.isShown())
                                    snackbar.dismiss();

                                final Bundle args = new Bundle();
                                args.putSerializable("result", result);

                                // Process move in a thread
                                // - the fragment could be gone
                                executor.submit(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            MessageTarget result = (MessageTarget) args.getSerializable("result");

                                            DB db = DB.getInstance(snackbar.getContext());
                                            try {
                                                db.beginTransaction();

                                                for (long id : result.ids) {
                                                    EntityMessage message = db.message().getMessage(id);
                                                    if (message != null && message.ui_hide) {
                                                        Log.i(Helper.TAG, "Move id=" + id + " target=" + result.target);
                                                        EntityFolder folder = db.folder().getFolderByName(message.account, result.target);
                                                        EntityOperation.queue(db, message, EntityOperation.MOVE, folder.id);
                                                    }
                                                }

                                                db.setTransactionSuccessful();
                                            } finally {
                                                db.endTransaction();
                                            }

                                            EntityOperation.process(snackbar.getContext());

                                        } catch (Throwable ex) {
                                            Log.e(Helper.TAG, ex + "\n" + Log.getStackTraceString(ex));
                                        }
                                    }
                                });
                            }
                        }, UNDO_TIMEOUT);
                    }

                    @Override
                    protected void onException(Bundle args, Throwable ex) {
                        Helper.unexpectedError(getContext(), ex);
                    }
                }.load(FragmentMessages.this, args);
            }

            class MessageTarget implements Serializable {
                List<Long> ids = new ArrayList<>();
                String target;
                String display;
            }
        }).attachToRecyclerView(rvMessage);

        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(getContext(), ActivityCompose.class)
                        .putExtra("action", "new")
                        .putExtra("account", (Long) fab.getTag())
                );
            }
        });

        View.OnClickListener navigate = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getFragmentManager().popBackStack("thread", FragmentManager.POP_BACK_STACK_INCLUSIVE);

                LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(getContext());
                lbm.sendBroadcast(
                        new Intent(ActivityView.ACTION_VIEW_THREAD)
                                .putExtra("account", account)
                                .putExtra("thread", (String) v.getTag()));
            }
        };

        fabPrev.setOnClickListener(navigate);
        fabNext.setOnClickListener(navigate);

        // Initialize
        tvNoEmail.setVisibility(View.GONE);
        grpReady.setVisibility(View.GONE);
        pbWait.setVisibility(View.VISIBLE);

        fab.hide();
        fabPrev.hide();
        fabNext.hide();

        return view;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLongArray("expanded", Helper.toLongArray(expanded));
        outState.putLongArray("headers", Helper.toLongArray(headers));
        outState.putLongArray("images", Helper.toLongArray(images));
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        if (savedInstanceState != null) {
            expanded = Helper.fromLongArray(savedInstanceState.getLongArray("expanded"));
            headers = Helper.fromLongArray(savedInstanceState.getLongArray("headers"));
            images = Helper.fromLongArray(savedInstanceState.getLongArray("images"));
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        grpHintSupport.setVisibility(prefs.getBoolean("app_support", false) ? View.GONE : View.VISIBLE);
        grpHintActions.setVisibility(prefs.getBoolean("message_actions", false) || viewType != AdapterMessage.ViewType.THREAD ? View.GONE : View.VISIBLE);

        final DB db = DB.getInstance(getContext());

        // Primary account
        db.account().livePrimaryAccount().observe(getViewLifecycleOwner(), new Observer<EntityAccount>() {
            @Override
            public void onChanged(EntityAccount account) {
                primary = (account == null ? -1 : account.id);
                connected = (account != null && "connected".equals(account.state));
                getActivity().invalidateOptionsMenu();
            }
        });

        // Folder
        switch (viewType) {
            case UNIFIED:
                db.folder().liveUnified().observe(getViewLifecycleOwner(), new Observer<List<TupleFolderEx>>() {
                    @Override
                    public void onChanged(List<TupleFolderEx> folders) {
                        int unseen = 0;
                        if (folders != null)
                            for (TupleFolderEx folder : folders)
                                unseen += folder.unseen;
                        String name = getString(R.string.title_folder_unified);
                        if (unseen > 0)
                            setSubtitle(getString(R.string.title_folder_unseen, name, unseen));
                        else
                            setSubtitle(name);
                    }
                });
                break;

            case FOLDER:
                db.folder().liveFolderEx(folder).observe(getViewLifecycleOwner(), new Observer<TupleFolderEx>() {
                    @Override
                    public void onChanged(@Nullable TupleFolderEx folder) {
                        if (folder == null)
                            setSubtitle(null);
                        else {
                            String name = (folder.display == null
                                    ? Helper.localizeFolderName(getContext(), folder.name)
                                    : folder.display);
                            if (folder.unseen > 0)
                                setSubtitle(getString(R.string.title_folder_unseen, name, folder.unseen));
                            else
                                setSubtitle(name);
                        }
                    }
                });
                break;

            case THREAD:
                setSubtitle(R.string.title_folder_thread);
                break;

            case SEARCH:
                setSubtitle(getString(R.string.title_searching, search));
                break;
        }

        // Messages
        loadMessages();

        // Compose FAB
        if (viewType == AdapterMessage.ViewType.THREAD) {
            String[] pn = ((ActivityView) getActivity()).getPrevNext(thread);

            fabPrev.setTag(pn[0]);
            fabNext.setTag(pn[1]);

            if (pn[0] == null)
                fabPrev.hide();
            else
                fabPrev.show();

            if (pn[1] == null)
                fabNext.hide();
            else
                fabNext.show();
        } else {
            Bundle args = new Bundle();
            args.putLong("account", account);

            new SimpleTask<Long>() {
                @Override
                protected Long onLoad(Context context, Bundle args) {
                    long account = args.getLong("account", -1);

                    if (account < 0) {
                        EntityFolder primary = DB.getInstance(context).folder().getPrimaryDrafts();
                        return (primary == null ? null : primary.account);
                    } else
                        return account;
                }

                @Override
                protected void onLoaded(Bundle args, Long account) {
                    if (account != null) {
                        fab.setTag(account);
                        fab.show();
                    }
                }

                @Override
                protected void onException(Bundle args, Throwable ex) {
                    Helper.unexpectedError(getContext(), ex);
                }
            }.load(this, args);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        grpSupport.setVisibility(Helper.isPro(getContext()) ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_list, menu);

        final MenuItem menuSearch = menu.findItem(R.id.menu_search);
        final SearchView searchView = (SearchView) menuSearch.getActionView();
        searchView.setQueryHint(getString(R.string.title_search_hint));
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                menuSearch.collapseActionView();

                if (Helper.isPro(getContext())) {
                    Intent intent = new Intent();
                    intent.putExtra("folder", folder);
                    intent.putExtra("search", query);

                    FragmentMessages fragment = new FragmentMessages();
                    fragment.setArguments(intent.getExtras());
                    FragmentTransaction fragmentTransaction = getFragmentManager().beginTransaction();
                    fragmentTransaction.replace(R.id.content_frame, fragment).addToBackStack("search");
                    fragmentTransaction.commit();
                } else {
                    FragmentTransaction fragmentTransaction = getFragmentManager().beginTransaction();
                    fragmentTransaction.replace(R.id.content_frame, new FragmentPro()).addToBackStack("pro");
                    fragmentTransaction.commit();
                }

                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                return false;
            }
        });

        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.menu_search).setVisible(folder >= 0 && search == null);
        menu.findItem(R.id.menu_sort_on).setVisible(TextUtils.isEmpty(search));
        menu.findItem(R.id.menu_folders).setVisible(primary >= 0);
        menu.findItem(R.id.menu_folders).setIcon(connected ? R.drawable.baseline_folder_24 : R.drawable.baseline_folder_open_24);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        String sort = prefs.getString("sort", "time");
        if ("time".equals(sort))
            menu.findItem(R.id.menu_sort_on_time).setChecked(true);
        else if ("unread".equals(sort))
            menu.findItem(R.id.menu_sort_on_unread).setChecked(true);
        else if ("starred".equals(sort))
            menu.findItem(R.id.menu_sort_on_starred).setChecked(true);

        super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());

        switch (item.getItemId()) {
            case R.id.menu_sort_on_time:
                prefs.edit().putString("sort", "time").apply();
                item.setChecked(true);
                loadMessages();
                return true;

            case R.id.menu_sort_on_unread:
            case R.id.menu_sort_on_starred:
                if (Helper.isPro(getContext())) {
                    prefs.edit().putString("sort", item.getItemId() == R.id.menu_sort_on_unread ? "unread" : "starred").apply();
                    item.setChecked(true);
                    loadMessages();
                } else {
                    FragmentTransaction fragmentTransaction = getFragmentManager().beginTransaction();
                    fragmentTransaction.replace(R.id.content_frame, new FragmentPro()).addToBackStack("pro");
                    fragmentTransaction.commit();
                }
                return true;

            case R.id.menu_folders:
                onMenuFolders();
                loadMessages();
                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void onMenuFolders() {
        getFragmentManager().popBackStack("unified", 0);

        Bundle args = new Bundle();
        args.putLong("account", primary);

        FragmentFolders fragment = new FragmentFolders();
        fragment.setArguments(args);

        FragmentTransaction fragmentTransaction = getFragmentManager().beginTransaction();
        fragmentTransaction.replace(R.id.content_frame, fragment).addToBackStack("folders");
        fragmentTransaction.commit();
    }

    private void loadMessages() {
        final DB db = DB.getInstance(getContext());

        // Observe folder/messages/search
        if (TextUtils.isEmpty(search)) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
            String sort = prefs.getString("sort", "time");
            boolean browse = prefs.getBoolean("browse", true);
            boolean debug = prefs.getBoolean("debug", false);

            if (messages != null)
                messages.removeObservers(getViewLifecycleOwner());

            switch (viewType) {
                case UNIFIED:
                    messages = new LivePagedListBuilder<>(db.message().pagedUnifiedInbox(sort, debug), LOCAL_PAGE_SIZE).build();
                    break;
                case FOLDER:
                    if (searchCallback == null)
                        searchCallback = new BoundaryCallbackMessages(
                                getContext(), this,
                                folder, null, REMOTE_PAGE_SIZE,
                                new BoundaryCallbackMessages.IBoundaryCallbackMessages() {
                                    @Override
                                    public void onLoading() {
                                        pbWait.setVisibility(View.VISIBLE);
                                    }

                                    @Override
                                    public void onLoaded() {
                                        pbWait.setVisibility(View.GONE);
                                    }

                                    @Override
                                    public void onError(Context context, Throwable ex) {
                                        if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED))
                                            new DialogBuilderLifecycle(getContext(), getViewLifecycleOwner())
                                                    .setMessage(Helper.formatThrowable(ex))
                                                    .setPositiveButton(android.R.string.cancel, null)
                                                    .create()
                                                    .show();
                                    }
                                });

                    PagedList.Config config = new PagedList.Config.Builder()
                            .setPageSize(LOCAL_PAGE_SIZE)
                            .setInitialLoadSizeHint(LOCAL_PAGE_SIZE)
                            .setPrefetchDistance(REMOTE_PAGE_SIZE)
                            .build();
                    LivePagedListBuilder<Integer, TupleMessageEx> builder = new LivePagedListBuilder<>(
                            db.message().pagedFolder(folder, sort, false, debug), config);
                    if (browse)
                        builder.setBoundaryCallback(searchCallback);
                    messages = builder.build();

                    break;
                case THREAD:
                    messages = new LivePagedListBuilder<>(db.message().pagedThread(account, thread, sort, debug), LOCAL_PAGE_SIZE).build();
                    break;
            }
        } else {
            if (searchCallback == null)
                searchCallback = new BoundaryCallbackMessages(
                        getContext(), this,
                        folder, search, REMOTE_PAGE_SIZE,
                        new BoundaryCallbackMessages.IBoundaryCallbackMessages() {
                            @Override
                            public void onLoading() {
                                tvNoEmail.setVisibility(View.GONE);
                                pbWait.setVisibility(View.VISIBLE);
                            }

                            @Override
                            public void onLoaded() {
                                pbWait.setVisibility(View.GONE);
                                if (searchCallback.getLoaded() == 0)
                                    tvNoEmail.setVisibility(View.VISIBLE);
                            }

                            @Override
                            public void onError(Context context, Throwable ex) {
                                if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED))
                                    new DialogBuilderLifecycle(getContext(), getViewLifecycleOwner())
                                            .setMessage(Helper.formatThrowable(ex))
                                            .setPositiveButton(android.R.string.cancel, null)
                                            .create()
                                            .show();
                            }
                        });

            PagedList.Config config = new PagedList.Config.Builder()
                    .setPageSize(LOCAL_PAGE_SIZE)
                    .setInitialLoadSizeHint(LOCAL_PAGE_SIZE)
                    .setPrefetchDistance(REMOTE_PAGE_SIZE)
                    .build();
            LivePagedListBuilder<Integer, TupleMessageEx> builder = new LivePagedListBuilder<>(
                    db.message().pagedFolder(folder, "time", true, false), config);
            builder.setBoundaryCallback(searchCallback);
            messages = builder.build();
        }

        messages.observe(getViewLifecycleOwner(), new Observer<PagedList<TupleMessageEx>>() {
            @Override
            public void onChanged(@Nullable PagedList<TupleMessageEx> messages) {
                if (messages == null ||
                        (viewType == AdapterMessage.ViewType.THREAD && messages.size() == 0)) {
                    finish();
                    return;
                }

                if (viewType != AdapterMessage.ViewType.THREAD)
                    ((ActivityView) getActivity()).setMessages(messages);

                if (viewType == AdapterMessage.ViewType.THREAD && autoExpand) {
                    autoExpand = false;

                    int count = 0;
                    int unseen = 0;
                    TupleMessageEx single = null;
                    TupleMessageEx see = null;
                    for (int i = 0; i < messages.size(); i++) {
                        TupleMessageEx message = messages.get(i);
                        if (!EntityFolder.ARCHIVE.equals(message.folderType)) {
                            count++;
                            single = message;
                            if (!message.ui_seen) {
                                unseen++;
                                see = message;
                            }
                        }
                    }

                    TupleMessageEx expand = null;
                    if (count == 1)
                        expand = single;
                    else if (unseen == 1)
                        expand = see;

                    if (expand != null) {
                        expanded.add(expand.id);
                        if (!expand.ui_seen)
                            handleExpand(expand.id);
                    }
                }

                Log.i(Helper.TAG, "Submit messages=" + messages.size());
                adapter.submitList(messages);

                boolean searching = (searchCallback != null && searchCallback.isSearching());

                if (!searching)
                    pbWait.setVisibility(View.GONE);
                grpReady.setVisibility(View.VISIBLE);

                if (messages.size() == 0) {
                    if (searchCallback == null)
                        tvNoEmail.setVisibility(View.VISIBLE);
                    rvMessage.setVisibility(View.GONE);
                } else {
                    tvNoEmail.setVisibility(View.GONE);
                    rvMessage.setVisibility(View.VISIBLE);
                }
            }
        });

    }

    private void handleExpand(long id) {
        Bundle args = new Bundle();
        args.putLong("id", id);

        new SimpleTask<Void>() {
            @Override
            protected Void onLoad(Context context, Bundle args) {
                long id = args.getLong("id");

                DB db = DB.getInstance(context);
                try {
                    db.beginTransaction();

                    EntityMessage message = db.message().getMessage(id);
                    EntityFolder folder = db.folder().getFolder(message.folder);

                    if (!EntityFolder.OUTBOX.equals(folder.type)) {
                        if (!message.content)
                            EntityOperation.queue(db, message, EntityOperation.BODY);

                        if (!message.ui_seen) {
                            db.message().setMessageUiSeen(message.id, true);
                            EntityOperation.queue(db, message, EntityOperation.SEEN, true);
                        }
                    }

                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }

                EntityOperation.process(context);

                return null;
            }

            @Override
            protected void onException(Bundle args, Throwable ex) {
                Helper.unexpectedError(getContext(), ex);
            }
        }.load(this, args);
    }
}
