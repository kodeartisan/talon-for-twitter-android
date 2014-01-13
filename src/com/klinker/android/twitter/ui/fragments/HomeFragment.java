package com.klinker.android.twitter.ui.fragments;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.Fragment;
import android.app.LoaderManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.Loader;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.AbsListView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.klinker.android.twitter.R;
import com.klinker.android.twitter.adapters.CursorListLoader;
import com.klinker.android.twitter.adapters.TimeLineCursorAdapter;
import com.klinker.android.twitter.data.App;
import com.klinker.android.twitter.data.sq_lite.HomeContentProvider;
import com.klinker.android.twitter.data.sq_lite.HomeDataSource;
import com.klinker.android.twitter.data.sq_lite.MentionsDataSource;
import com.klinker.android.twitter.services.TalonPullNotificationService;
import com.klinker.android.twitter.services.TimelineRefreshService;
import com.klinker.android.twitter.ui.MainActivity;
import com.klinker.android.twitter.ui.drawer_activities.DrawerActivity;
import com.klinker.android.twitter.ui.widgets.HoloTextView;
import com.klinker.android.twitter.utils.Utils;

import org.lucasr.smoothie.AsyncListView;
import org.lucasr.smoothie.ItemManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import twitter4j.FilterQuery;
import twitter4j.Paging;
import twitter4j.StallWarning;
import twitter4j.Status;
import twitter4j.StatusDeletionNotice;
import twitter4j.StatusListener;
import twitter4j.Twitter;
import twitter4j.TwitterException;
import twitter4j.TwitterStream;
import twitter4j.User;
import uk.co.senab.actionbarpulltorefresh.library.ActionBarPullToRefresh;
import uk.co.senab.actionbarpulltorefresh.library.Options;
import uk.co.senab.actionbarpulltorefresh.library.PullToRefreshLayout;
import uk.co.senab.actionbarpulltorefresh.library.listeners.OnRefreshListener;
import uk.co.senab.bitmapcache.BitmapLruCache;

public class HomeFragment extends Fragment implements OnRefreshListener, LoaderManager.LoaderCallbacks<Cursor> {

    public static final int HOME_REFRESH_ID = 121;

    private static Twitter twitter;

    public static AsyncListView listView;
    private TimeLineCursorAdapter cursorAdapter;

    private SharedPreferences sharedPrefs;

    private PullToRefreshLayout mPullToRefreshLayout;

    private HomeDataSource dataSource;

    private static int unread;

    static Activity context;

    private ActionBar actionBar;
    private int mActionBarSize;

    private boolean initial = true;
    private boolean shown = true;
    private boolean landscape;
    public boolean newTweets = false;

    private String jumpToTop;
    private String fromTop;
    private String toMentions;
    private String allRead;

    private View.OnClickListener toTopListener;
    private View.OnClickListener toMentionsListener;
    private View.OnClickListener liveStreamRefresh;

    public TwitterStream twitterStream;

    public View view;

    public BroadcastReceiver pullReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int unreadtweets = dataSource.getUnreadCount(DrawerActivity.settings.currentAccount);
            markReadForLoad();
            if (unreadtweets != 0) {
                showToastBar(unreadtweets + " " + (unreadtweets == 1 ? getResources().getString(R.string.new_tweet) : getResources().getString(R.string.new_tweets)),
                        getResources().getString(R.string.view_new),
                        400,
                        true,
                        liveStreamRefresh);
            }

            newTweets = true;
        }
    };

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        context = activity;
        actionBar = context.getActionBar();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);

        Log.v("setting_fragments", "home fragment");

        if(DrawerActivity.settings.pushNotifications) {
            context.startService(new Intent(context, TalonPullNotificationService.class));
        } else {
            context.sendBroadcast(new Intent("com.klinker.android.twitter.STOP_PUSH_SERVICE"));
        }

        landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;

        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);

        sharedPrefs.edit().putBoolean("refresh_me", false).commit();

        jumpToTop = getResources().getString(R.string.jump_to_top);
        fromTop = getResources().getString(R.string.from_top);
        toMentions = getResources().getString(R.string.mentions);
        allRead = getResources().getString(R.string.all_read);

        try{
            final TypedArray styledAttributes = context.getTheme().obtainStyledAttributes(
                    new int[] { android.R.attr.actionBarSize });
            mActionBarSize = (int) styledAttributes.getDimension(0, 0);
            styledAttributes.recycle();
        } catch (Exception e) {
            // a default just in case i guess...
            mActionBarSize = toDP(48);
        }

        View layout = inflater.inflate(R.layout.main_fragments, null);

        dataSource = new HomeDataSource(context);
        dataSource.open();

        LinearLayout spinner = (LinearLayout) layout.findViewById(R.id.spinner);
        spinner.setVisibility(View.GONE);

        listView = (AsyncListView) layout.findViewById(R.id.listView);
        listView.setVisibility(View.VISIBLE);

        getLoaderManager().initLoader(0, null, this);

        // Now find the PullToRefreshLayout to setup
        mPullToRefreshLayout = (PullToRefreshLayout) layout.findViewById(R.id.ptr_layout);

        // Now setup the PullToRefreshLayout
        ActionBarPullToRefresh.from(context)
                // set up the scroll distance
                .options(Options.create().scrollDistance(.4f).build())
                // Mark All Children as pullable
                .allChildrenArePullable()
                        // Set the OnRefreshListener
                .listener(this)
                        // Finally commit the setup to our PullToRefreshLayout
                .setup(mPullToRefreshLayout);

        BitmapLruCache cache = App.getInstance(context).getBitmapCache();
        CursorListLoader loader = new CursorListLoader(cache, context);

        ItemManager.Builder builder = new ItemManager.Builder(loader);
        builder.setPreloadItemsEnabled(true).setPreloadItemsCount(50);
        builder.setThreadPoolSize(4);

        listView.setItemManager(builder.build());
        listView.setOverScrollMode(ListView.OVER_SCROLL_NEVER);

        View viewHeader = context.getLayoutInflater().inflate(R.layout.ab_header, null);
        listView.addHeaderView(viewHeader, null, false);
        listView.setHeaderDividersEnabled(false);

        if (DrawerActivity.translucent) {
            if (Utils.hasNavBar(context)) {
                View footer = new View(context);
                footer.setOnClickListener(null);
                footer.setOnLongClickListener(null);
                ListView.LayoutParams params = new ListView.LayoutParams(ListView.LayoutParams.MATCH_PARENT, Utils.getNavBarHeight(context));
                footer.setLayoutParams(params);
                listView.addFooterView(footer);
                listView.setFooterDividersEnabled(false);
            }

            if (!MainActivity.isPopup) {
                View view = new View(context);
                view.setOnClickListener(null);
                view.setOnLongClickListener(null);
                ListView.LayoutParams params2 = new ListView.LayoutParams(ListView.LayoutParams.MATCH_PARENT, Utils.getStatusBarHeight(context));
                view.setLayoutParams(params2);
                listView.addHeaderView(view);
                listView.setHeaderDividersEnabled(false);
            }
        }

        final int currentAccount = sharedPrefs.getInt("current_account", 1);
        final boolean isTablet = getResources().getBoolean(R.bool.isTablet);

        listView.setOnScrollListener(new AbsListView.OnScrollListener() {

            int mLastFirstVisibleItem = 0;

            @Override
            public void onScrollStateChanged(AbsListView absListView, int i) {

            }

            @Override
            public void onScroll(AbsListView absListView, final int firstVisibleItem, int visibleItemCount, int totalItemCount) {

                if (newTweets && firstVisibleItem == 0) {
                    final int unread = dataSource.getUnreadCount(currentAccount);
                    if (unread > 0) {
                        showToastBar(unread + " " + (unread == 1 ? getResources().getString(R.string.new_tweet) : getResources().getString(R.string.new_tweets)),
                                getResources().getString(R.string.view_new),
                                400,
                                true,
                                liveStreamRefresh);
                    }
                }

                if (DrawerActivity.settings.uiExtras) {
                    if (firstVisibleItem != 0) {
                        if (MainActivity.canSwitch) {
                            // used to show and hide the action bar
                            if (firstVisibleItem < 3) {

                            } else if (firstVisibleItem < mLastFirstVisibleItem) {
                                if (!landscape && !isTablet) {
                                    actionBar.hide();
                                }
                                if (!isToastShowing) {
                                    showToastBar(firstVisibleItem + " " + fromTop, jumpToTop, 400, false, toTopListener);
                                }
                            } else if (firstVisibleItem > mLastFirstVisibleItem) {
                                if (!landscape && !isTablet) {
                                    actionBar.show();
                                }
                                if (isToastShowing && !infoBar) {
                                    hideToastBar(400);
                                }
                            }

                            mLastFirstVisibleItem = firstVisibleItem;
                        }
                    } else {
                        if (!landscape && !isTablet) {
                            actionBar.show();
                        }
                        if (!infoBar) {
                            hideToastBar(400);
                        }
                    }

                    if (isToastShowing && !infoBar) {
                        updateToastText(firstVisibleItem + " " + fromTop);
                    }

                    if (MainActivity.translucent && actionBar.isShowing()) {
                        showStatusBar();
                    } else if (MainActivity.translucent) {
                        hideStatusBar();
                    }
                }

            }
        });

        view = layout;

        setUpToastBar(layout);

        toTopListener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    if (Integer.parseInt(toastDescription.getText().toString().split(" ")[0]) > 100) {
                        listView.setSelection(0);
                    } else {
                        listView.smoothScrollToPosition(0);
                    }
                } catch (Exception e) {
                    listView.smoothScrollToPosition(0);
                }
            }
        };

        toMentionsListener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                MainActivity.mViewPager.setCurrentItem(DrawerActivity.settings.extraPages ? 3 : 1, true);
            }
        };

        liveStreamRefresh = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                newTweets = false;
                getLoaderManager().restartLoader(0, null, HomeFragment.this);
                //int size = toDP(5) + mActionBarSize + (DrawerActivity.translucent ? DrawerActivity.statusBarHeight : 0);
                listView.setSelectionFromTop(0, 0);
                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        hideToastBar(400);
                        dataSource.markAllRead(currentAccount);
                        context.sendBroadcast(new Intent("com.klinker.android.twitter.NEW_TWEET"));
                    }
                }, 300);

                context.sendBroadcast(new Intent("com.klinker.android.talon.UPDATE_WIDGET"));
            }
        };

        return layout;
    }

    public List<twitter4j.Status> getList(int page, Twitter twitter) {
        try {
            return twitter.getHomeTimeline(new Paging(page, 200));
        } catch (Exception e) {
            return new ArrayList<twitter4j.Status>();
        }
    }

    public int doRefresh() {
        int numberNew = 0;

        try {
            int currentAccount = sharedPrefs.getInt("current_account", 1);

            dataSource.markAllRead(currentAccount);
            context.sendBroadcast(new Intent("com.klinker.android.twitter.NEW_TWEET"));

            twitter = Utils.getTwitter(context, DrawerActivity.settings);

            User user = twitter.verifyCredentials();
            long lastId = dataSource.getLastId(currentAccount);
            long secondToLastId = sharedPrefs.getLong("second_last_tweet_id_" + currentAccount, 0);

            List<twitter4j.Status> statuses = new ArrayList<twitter4j.Status>();

            boolean foundStatus = false;
            int lastJ = 0;

            for (int i = 0; i < DrawerActivity.settings.maxTweetsRefresh; i++) {
                if (foundStatus) {
                    break;
                } else {
                    statuses.addAll(getList(i + 1, twitter));
                }

                try {
                    for (int j = lastJ; j < statuses.size(); j++) {
                        long id = statuses.get(j).getId();
                        if (id == lastId || id == secondToLastId) {
                            statuses = statuses.subList(0, j);
                            foundStatus = true;
                            break;
                        }
                    }
                } catch (Exception e) {
                    foundStatus = true;
                }

                lastJ = statuses.size();
            }

            if (statuses.size() != 0) {
                try {
                    sharedPrefs.edit().putLong("second_last_tweet_id_" + currentAccount, statuses.get(1).getId()).commit();
                } catch (Exception e) {
                    sharedPrefs.edit().putLong("second_last_tweet_id_" + currentAccount, sharedPrefs.getLong("last_tweet_id_" + currentAccount, 0)).commit();
                }
                sharedPrefs.edit().putLong("last_tweet_id_" + currentAccount, statuses.get(0).getId()).commit();

            }

            for (twitter4j.Status status : statuses) {
                try {
                    if (!dataSource.tweetExists(status.getId(), currentAccount)) {
                        HomeContentProvider.insertTweet(status, currentAccount, context);
                    }
                    //dataSource.createTweet(status, currentAccount);
                } catch (Exception e) {
                    e.printStackTrace();
                    break;
                }
            }

            numberNew = dataSource.getUnreadCount(currentAccount);
            unread = numberNew;

        } catch (TwitterException e) {
            // Error in updating status
            Log.d("Twitter Update Error", e.getMessage());
        }


        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

        long now = new Date().getTime();
        long alarm = now + DrawerActivity.settings.timelineRefresh;

        PendingIntent pendingIntent = PendingIntent.getService(context, HOME_REFRESH_ID, new Intent(context, TimelineRefreshService.class), 0);

        if (DrawerActivity.settings.timelineRefresh != 0)
            am.setRepeating(AlarmManager.RTC_WAKEUP, alarm, DrawerActivity.settings.timelineRefresh, pendingIntent);
        else
            am.cancel(pendingIntent);

        return numberNew;
    }

    @Override
    public void onRefreshStarted(final View view) {
        new AsyncTask<Void, Void, Void>() {

            private int numberNew;

            @Override
            protected void onPreExecute() {
                DrawerActivity.canSwitch = false;
            }

            @Override
            protected Void doInBackground(Void... params) {

                numberNew = doRefresh();

                return null;
            }

            @Override
            protected void onPostExecute(Void result) {
                try {
                    super.onPostExecute(result);

                    if (unread > 0) {
                        getLoaderManager().restartLoader(0, null, HomeFragment.this);

                        CharSequence text = numberNew == 1 ?  numberNew + " " + getResources().getString(R.string.new_tweet) :  numberNew + " " + getResources().getString(R.string.new_tweets);
                        showToastBar(text + "", jumpToTop, 400, true, toTopListener);
                    } else {
                        CharSequence text = context.getResources().getString(R.string.no_new_tweets);

                        showToastBar(text + "", allRead, 400, true, toTopListener);
                    }

                    DrawerActivity.canSwitch = true;

                    mPullToRefreshLayout.setRefreshComplete();
                    newTweets = false;

                    new RefreshMentions().execute();
                } catch (Exception e) {
                    DrawerActivity.canSwitch = true;

                    try {
                        mPullToRefreshLayout.setRefreshComplete();
                    } catch (Exception x) {
                        // not attached to the activity i guess, don't know how or why that would be though
                    }
                }

                context.sendBroadcast(new Intent("com.klinker.android.talon.UPDATE_WIDGET"));
            }
        }.execute();
    }

    class RefreshMentions extends AsyncTask<Void, Void, Boolean> {

        private boolean update = false;
        private int numberNew = 0;

        @Override
        protected void onPreExecute() {
            DrawerActivity.canSwitch = false;
        }

        protected Boolean doInBackground(Void... args) {

            try {
                twitter = Utils.getTwitter(context, DrawerActivity.settings);

                int currentAccount = sharedPrefs.getInt("current_account", 1);

                User user = twitter.verifyCredentials();
                MentionsDataSource dataSource = new MentionsDataSource(context);
                dataSource.open();
                long[] lastId = dataSource.getLastIds(currentAccount);
                dataSource.close();
                Paging paging;
                paging = new Paging(1, 50);

                List<twitter4j.Status> statuses = twitter.getMentionsTimeline(paging);

                boolean broken = false;

                // first try to get the top 50 tweets
                for (int i = 0; i < statuses.size(); i++) {
                    long id = statuses.get(i).getId();
                    if (id == lastId[0] || id == lastId[1]) {
                        statuses = statuses.subList(0, i);
                        broken = true;
                        break;
                    }
                }

                // if that doesn't work, then go for the top 150
                if (!broken) {
                    Log.v("updating_timeline", "not broken");
                    Paging paging2 = new Paging(1, 150);
                    List<twitter4j.Status> statuses2 = twitter.getHomeTimeline(paging2);

                    for (int i = 0; i < statuses2.size(); i++) {
                        long id = statuses2.get(i).getId();
                        if (id == lastId[0] || id == lastId[1]) {
                            statuses2 = statuses2.subList(0, i);
                            break;
                        }
                    }

                    statuses = statuses2;
                }

                if (statuses.size() != 0) {
                    sharedPrefs.edit().putLong("last_mention_id_" + currentAccount, statuses.get(0).getId()).commit();
                    update = true;
                    numberNew = statuses.size();
                } else {
                    update = false;
                    numberNew = 0;
                }

                /*MentionsDataSource dataSource = new MentionsDataSource(context);
                dataSource.open();

                for (twitter4j.Status status : statuses) {
                    try {
                        dataSource.createTweet(status, currentAccount);
                    } catch (Exception e) {
                        break;
                    }
                }

                dataSource.close();*/

            } catch (TwitterException e) {
                // Error in updating status
                Log.d("Twitter Update Error", e.getMessage());
            }

            return update;
        }

        protected void onPostExecute(Boolean updated) {

            try {
                if (updated) {
                    sharedPrefs.edit().putBoolean("refresh_me_mentions", true).commit();
                    CharSequence text = numberNew == 1 ?  numberNew + " " + getResources().getString(R.string.new_mention) :  numberNew + " " + getResources().getString(R.string.new_mentions);
                    showToastBar(text + "", toMentions, 400, true, toMentionsListener);
                } else {

                }
            } catch (Exception e) {
                // might happen when switching accounts from the notification for second accounts mentions
            }

            DrawerActivity.canSwitch = true;
        }

    }

    @Override
    public void onPause() {
        int mUnread = listView.getFirstVisiblePosition();
        sharedPrefs.edit().putInt("timeline_unread", mUnread).commit();

        if (unread > 0) {
            int currentAccount = sharedPrefs.getInt("current_account", 1);
            dataSource.markMultipleRead(mUnread, currentAccount);
            context.sendBroadcast(new Intent("com.klinker.android.twitter.NEW_TWEET"));
            unread = mUnread;
        }

        try {
            context.unregisterReceiver(pullReceiver);
        } catch (Exception e) { }

        super.onPause();
    }

    public Long[] fIds;
    public long[] ids;

    @Override
    public void onResume() {
        super.onResume();

        if (sharedPrefs.getBoolean("refresh_me", false)) {
            getLoaderManager().restartLoader(0, null, HomeFragment.this);
        }

        sharedPrefs.edit().putBoolean("refresh_me", false).commit();

        IntentFilter filter = new IntentFilter();
        filter.addAction("com.klinker.android.twitter.NEW_TWEET");
        context.registerReceiver(pullReceiver, filter);
    }

    public boolean justStarted = false;
    public Handler waitOnRefresh = new Handler();
    public Runnable applyRefresh = new Runnable() {
        @Override
        public void run() {
            sharedPrefs.edit().putBoolean("should_refresh", true).commit();
        }
    };

    @Override
    public void onStart() {
        super.onStart();

        justStarted = true;

        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if((DrawerActivity.settings.refreshOnStart) && listView.getFirstVisiblePosition() == 0 && !MainActivity.isPopup && sharedPrefs.getBoolean("should_refresh", true)) {
                    mPullToRefreshLayout.setRefreshing(true);
                    onRefreshStarted(view);
                }

                waitOnRefresh.removeCallbacks(applyRefresh);
                waitOnRefresh.postDelayed(applyRefresh, 30000);
            }
        }, 250);
    }


    public int toDP(int px) {
        try {
            return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, px, getResources().getDisplayMetrics());
        } catch (Exception e) {
            return px;
        }
    }

    public void showStatusBar() {
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                DrawerActivity.statusBar.setVisibility(View.VISIBLE);
            }
        }, 000);
    }

    public void hideStatusBar() {
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                DrawerActivity.statusBar.setVisibility(View.GONE);
            }
        }, 000); // 200 would be better
    }


    @Override
    public Loader<Cursor> onCreateLoader(int i, Bundle bundle) {
        String[] projection = HomeDataSource.allColumns;
        CursorLoader cursorLoader = new CursorLoader(
                context,
                HomeContentProvider.CONTENT_URI,
                projection,
                null,
                new String[] { sharedPrefs.getInt("current_account", 1) + "" },
                null );
        return cursorLoader;
    }

    @Override
    public void onLoadFinished(Loader<Cursor> cursorLoader, Cursor cursor) {
        cursorAdapter = new TimeLineCursorAdapter(context, cursor, false);
        listView.setAdapter(cursorAdapter);

        initial = false;

        int currentAccount = sharedPrefs.getInt("current_account", 1);
        int newTweets = dataSource.getUnreadCount(currentAccount);

        if (newTweets != 0) {
            unread = newTweets;
            int size = mActionBarSize + (DrawerActivity.translucent ? DrawerActivity.statusBarHeight : 0);
            listView.setSelectionFromTop(newTweets + (MainActivity.isPopup || landscape ? 1 : 2), size);
        }
    }

    @Override
    public void onLoaderReset(Loader<Cursor> cursorLoader) {
        // data is not available anymore, delete reference
        cursorAdapter.swapCursor(null);
    }

    private boolean isToastShowing = false;
    private boolean infoBar = false;

    private View toastBar;
    private TextView toastDescription;
    private TextView toastButton;

    private void setUpToastBar(View view) {
        toastBar = view.findViewById(R.id.toastBar);
        toastDescription = (TextView) view.findViewById(R.id.toastDescription);
        toastButton = (TextView) view.findViewById(R.id.toastButton);
    }

    private void showToastBar(String description, String buttonText, final long length, final boolean quit, View.OnClickListener listener) {
        toastDescription.setText(description);
        toastButton.setText(buttonText);
        toastButton.setOnClickListener(listener);

        toastBar.setVisibility(View.VISIBLE);

        Animation anim = AnimationUtils.loadAnimation(context, R.anim.slide_in_right);
        anim.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
                isToastShowing = true;
                if (quit) {
                    infoBar = true;
                }
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                if (quit) {
                    new Handler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            hideToastBar(length);
                            infoBar = false;
                        }
                    }, 3000);
                }
            }

            @Override
            public void onAnimationRepeat(Animation animation) {

            }
        });
        anim.setDuration(length);
        toastBar.startAnimation(anim);
    }

    private void hideToastBar(long length) {
        if (!isToastShowing) {
            return;
        }

        Animation anim = AnimationUtils.loadAnimation(context, R.anim.slide_out_right);
        anim.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
                isToastShowing = false;
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                toastBar.setVisibility(View.GONE);
            }

            @Override
            public void onAnimationRepeat(Animation animation) {

            }
        });
        anim.setDuration(length);
        toastBar.startAnimation(anim);
    }

    public void updateToastText(String text) {
        if(isToastShowing) {
            toastDescription.setText(text);
        }
    }

    public void markReadForLoad() {
        try {
            int current = listView.getFirstVisiblePosition();
            TextView tweetText = (TextView) listView.getChildAt(current).findViewById(R.id.tweet);
            String text = tweetText.getText().toString();

            dataSource.markMultipleRead(text, DrawerActivity.settings.currentAccount);
        } catch (Exception e) {

        }
    }
}