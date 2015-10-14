package com.leapmotion.example.music;

import android.content.Context;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.BaseColumns;
import android.provider.MediaStore;
import android.support.v4.app.Fragment;
import android.support.v4.view.PagerAdapter;
import android.support.v4.view.ViewPager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.leapmotion.leap.CircleGesture;
import com.leapmotion.leap.Controller;
import com.leapmotion.leap.Frame;
import com.leapmotion.leap.Gesture;
import com.leapmotion.leap.GestureList;
import com.leapmotion.leap.Listener;
import com.leapmotion.leap.SwipeGesture;
import com.leapmotion.leap.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class PlayerFragment extends Fragment {
    private static final String TAG = "PlayerFragment";
    private MediaPlayer mMediaPlayer;
    private Controller mController;
    private Listener mListener;
    private List<TrackInfo> mTracks;
    private int mCurrentTrack;
    private ProgressBar mVolumeView;
    private Timer mTimer;
    private AudioManager mAudioManager;
    private ViewPager mViewPager;
    private TrackPagerAdapter mPagerAdapter;
    private MediaMetadataRetriever mMediaMetaData;
    private ImageButton mPlayPauseButton;
    private ProgressBar mProgressView;
    private TextView mProgressTextView;
    private boolean mHandDetected;
    private Object mLock = new Object();
    private View mRootView;
    private TextView mTrackProgressTextView;

    public PlayerFragment() {
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPagerAdapter = new TrackPagerAdapter();
        mMediaMetaData = new MediaMetadataRetriever();
        mTracks = new ArrayList<TrackInfo>();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        mRootView = inflater.inflate(R.layout.fragment_main, container, false);

        mViewPager = (ViewPager) mRootView.findViewById(R.id.view_pager);
        mViewPager.setAdapter(mPagerAdapter);
        mViewPager.setOnPageChangeListener(mPagerAdapter);

        mVolumeView = (ProgressBar) mRootView.findViewById(R.id.progress_bar_volume);
        mProgressView = (ProgressBar) mRootView.findViewById(R.id.progress_bar);
        mTrackProgressTextView = (TextView) mRootView.findViewById(R.id.text_track_progress);
        mProgressTextView = (TextView) mRootView.findViewById(R.id.text_progress);
        mPlayPauseButton = (ImageButton) mRootView.findViewById(R.id.btn_play_pause);

        return mRootView;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        mAudioManager = (AudioManager) getActivity().getSystemService(Context.AUDIO_SERVICE);
        findTracks();
    }

    @Override
    public void onStart() {
        super.onStart();

        mController = new Controller();
        mController.enableGesture(Gesture.Type.TYPE_SWIPE);
        mController.enableGesture(Gesture.Type.TYPE_KEY_TAP);
        mController.enableGesture(Gesture.Type.TYPE_CIRCLE);

        mListener = new MusicControlsListener();
        mController.addListener(mListener);

        mTimer = new Timer();
        mTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                updateDisplay();
            }
        }, 0, 500);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (mMediaPlayer != null) {
            mMediaPlayer.release();
            mMediaPlayer = null;
        }
        mController.removeListener(mListener);
        mController.delete();
        mListener.delete();
        mTimer.cancel();
    }

    private void findTracks() {
        new AsyncTask<Void, Void, Void>() {

            @Override
            protected Void doInBackground(Void... voids) {
                Cursor cursor = getActivity().getContentResolver().query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        null,
                        MediaStore.Audio.Media.IS_MUSIC + " != 0",
                        null,
                        BaseColumns._ID + " limit 20"
                );

                if (cursor == null) {
                    return null;
                }

                int dataColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DATA);
                int idColumn = cursor.getColumnIndex(MediaStore.Audio.Media._ID);
                while (cursor.moveToNext()) {

                    Uri uri = MediaStore.Audio.Media.getContentUriForPath(cursor.getString(dataColumn));
                    Log.d(TAG, Uri.withAppendedPath(uri, cursor.getString(idColumn)).toString());
                    mTracks.add(new TrackInfo(Uri.withAppendedPath(uri, cursor.getString(idColumn))));
                }

                return null;
            }

            @Override
            protected void onPostExecute(Void aVoid) {
                mPagerAdapter.setTracks(mTracks);
                if (mTracks.size() > 0) {
                    changeTrack();
                }
            }
        }.execute();
    }

    private Runnable updateDisplayRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (mLock) {
                mVolumeView.setMax(mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                mVolumeView.setProgress(mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC));

                if (mMediaPlayer != null) {
                    if (mMediaPlayer.isPlaying()) {
                        mPlayPauseButton.setImageResource(R.drawable.ic_action_pause);
                    } else {
                        mPlayPauseButton.setImageResource(R.drawable.ic_action_play);
                    }

                    int totalTime = mMediaPlayer.getDuration();
                    int currentTime = mMediaPlayer.getCurrentPosition();

                    String currentTimeStr = msToTime(currentTime);
                    String totalTimeStr = msToTime(totalTime);
                    mProgressView.setMax(totalTime);
                    mProgressView.setProgress(currentTime);

                    mProgressTextView.setText(String.format("%s / %s", currentTimeStr, totalTimeStr));
                    mTrackProgressTextView.setText(String.format("track %d of %d", mCurrentTrack + 1, mTracks.size()));
                }
                mViewPager.setCurrentItem(mCurrentTrack);

                if (mHandDetected) {
                    mRootView.setBackgroundColor(0xffffffff);
                } else {
                    mRootView.setBackgroundColor(0xff666666);
                }
            }
        }
    };

    private String msToTime(int ms) {
        int seconds = ms / 1000;
        int minutes = seconds / 60;
        return String.format("%d:%02d", minutes, seconds % 60);
    }

    private void updateDisplay() {
        getActivity().runOnUiThread(updateDisplayRunnable);
    }

    private void togglePlayPause() {
        synchronized (mLock) {
            if (mTracks.size() <= 0) {
                return;
            }
            if (mMediaPlayer == null) {
                mMediaPlayer = MediaPlayer.create(getActivity(), mTracks.get(mCurrentTrack).uri);
            }

            if (mMediaPlayer.isPlaying()) {
                mMediaPlayer.pause();
            } else {
                mMediaPlayer.start();
            }
        }
        updateDisplay();
    }

    private void nextTrack() {
        mCurrentTrack = Math.min(mCurrentTrack + 1, mTracks.size() - 1);

        changeTrack();
    }

    private void previousTrack() {
        mCurrentTrack = Math.max(mCurrentTrack - 1, 0);

        changeTrack();
    }

    private void changeTrack() {
        synchronized (mLock) {
            if (mTracks.size() <= 0) {
                return;
            }

            boolean isPlaying = false;

            if (mMediaPlayer != null) {
                isPlaying = mMediaPlayer.isPlaying();
                mMediaPlayer.release();
            }
            mMediaPlayer = MediaPlayer.create(getActivity(), mTracks.get(mCurrentTrack).uri);

            if (isPlaying) {
                mMediaPlayer.start();
            }
        }
        updateDisplay();
    }

    private void adjustVolume(boolean up) {
        int direction = up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
        mAudioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0);
        updateDisplay();
    }

    private class TrackPagerAdapter extends PagerAdapter implements ViewPager.OnPageChangeListener {

        private List<TrackInfo> mTrackList;

        public void setTracks(List<TrackInfo> tracks) {
            mTrackList = tracks;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            if (mTrackList == null) {
                return 0;
            }
            return mTrackList.size();
        }

        @Override
        public boolean isViewFromObject(View view, Object o) {
            return view == o;
        }

        @Override
        public Object instantiateItem(ViewGroup container, int position) {
            View trackView = View.inflate(getActivity(), R.layout.track_view, null);
            ((TextView) trackView.findViewById(R.id.text_artist)).setText(mTrackList.get(position).artist);
            ((TextView) trackView.findViewById(R.id.text_title)).setText(mTrackList.get(position).title);
            mViewPager.addView(trackView);
            return trackView;
        }

        @Override
        public void destroyItem(ViewGroup container, int position, Object object) {
            mViewPager.removeView((View) object);
        }

        @Override
        public void onPageScrolled(int i, float v, int i2) {
        }

        @Override
        public void onPageSelected(int i) {
            if (mCurrentTrack == i) {
                return;
            }
            mCurrentTrack = i;
            changeTrack();
        }

        @Override
        public void onPageScrollStateChanged(int i) {
        }
    }

    private class MusicControlsListener extends Listener {

        private static final String TAG = "MusicControlsListener";
        private static final int CIRCLE_THROTTLE_TIME = 300; //ms
        private static final int SWIPE_THROTTLE_TIME = 1200; //ms
        private long mCircleGestureThrottle;
        private long mSwipeGestureThrottle;

        @Override
        public void onFrame(Controller controller) {
            Frame frame = controller.frame();
            mHandDetected = frame.pointables().count() > 0;
            processGestures(frame);
        }

        private void processGestures(Frame frame) {
            GestureList gestures = frame.gestures();
            for (int i = 0; i < gestures.count(); i++) {
                Gesture gesture = gestures.get(i);

                long now = System.currentTimeMillis();
                switch (gesture.type()) {
                    case TYPE_SWIPE:
                        if (gesture.state() != Gesture.State.STATE_START) {
                            break;
                        }
                        if (now - mSwipeGestureThrottle < SWIPE_THROTTLE_TIME) {
                            break;
                        }
                        mSwipeGestureThrottle = now;

                        SwipeGesture swipe = new SwipeGesture(gesture);
                        Vector direction = swipe.direction();
                        float xPortion = direction.getX() / Math.abs(direction.getY());

                        if (xPortion > 0.5f) {
                            previousTrack();
                        } else if (xPortion < -0.5f) {
                            nextTrack();
                        }
                        break;

                    case TYPE_KEY_TAP:
                        togglePlayPause();
                        break;

                    case TYPE_CIRCLE:
                        if (now - mCircleGestureThrottle < CIRCLE_THROTTLE_TIME) {
                            break;
                        }
                        mCircleGestureThrottle = now;

                        CircleGesture circleGesture = new CircleGesture(gesture);
                        boolean clockWise = circleGesture.pointable().direction().angleTo(circleGesture.normal()) <= Math.PI / 2;
                        adjustVolume(clockWise);
                        break;
                }
            }
        }
    }

    private class TrackInfo {
        Uri uri;
        String artist;
        String title;

        public TrackInfo(Uri uri) {
            this.uri = uri;
            mMediaMetaData.setDataSource(getActivity(), uri);
            artist = mMediaMetaData.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            title = mMediaMetaData.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
        }
    }
}
