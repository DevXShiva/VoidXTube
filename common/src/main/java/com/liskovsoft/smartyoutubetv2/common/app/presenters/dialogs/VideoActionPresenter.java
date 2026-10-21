package com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs;

import android.content.Context;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelUploadsPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SearchPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.utils.LoadingManager;

public class VideoActionPresenter extends BasePresenter<Void> {
    private static final String TAG = VideoActionPresenter.class.getSimpleName();

    /**
     * NEWTUBE(same-video-tap): the phone's way back to a player that already plays the tapped
     * video (PiP window or in-app mini player): expand it instead of opening the video again.
     */
    public interface PlayingReturn {
        /** True when the tap was answered by bringing the playing player back. */
        boolean bringToFront(Video tapped);
    }

    /** Set once from the phone's Application; the TV flavors never set it (every tap opens). */
    private static volatile PlayingReturn sPlayingReturn;

    public static void setPlayingReturn(PlayingReturn playingReturn) {
        sPlayingReturn = playingReturn;
    }

    private VideoActionPresenter(Context context) {
        super(context);
    }

    public static VideoActionPresenter instance(Context context) {
        return new VideoActionPresenter(context);
    }

    public void apply(Video item) {
        if (item == null) {
            return;
        }

        // Show playlist contents in channel instead of instant playback
        if (item.hasVideo() && !item.isBadgePlaylistInChannel()) {
            PlayingReturn playingReturn = sPlayingReturn;
            if (playingReturn != null && playingReturn.bringToFront(item)) {
                return;
            }
            PlaybackPresenter.instance(getContext()).openVideo(item);
        } else if (item.hasChannel() || (item.belongsToChannelUploads() && item.hasNestedItems())) {
            MediaServiceManager.chooseChannelPresenter(getContext(), item);
        } else if (item.hasPlaylist() || item.hasNestedItems()) {
            if (item.belongsToMusic()) {
                startFistPlaylistItem(item);
            } else {
                ChannelUploadsPresenter.instance(getContext()).openChannel(item);
            }
        } else if (item.isChapter) {
            PlaybackPresenter.instance(getContext()).setPosition(item.startTimeMs);
        } else if (item.searchQuery != null ) {
            SearchPresenter.instance(getContext()).onSearch(item.searchQuery);
        } else {
            MessageHelpers.showMessage(getContext(), "Video item doesn't contain needed data!");
        }
    }

    private void startFistPlaylistItem(Video item) {
        LoadingManager.showLoading(getContext(), true);
        ChannelUploadsPresenter.instance(getContext()).obtainGroup(item, mediaGroup -> {
            LoadingManager.showLoading(getContext(), false);
            if (!mediaGroup.isEmpty()) {
                PlaybackPresenter.instance(getContext()).openVideo(Video.from(mediaGroup.getMediaItems().get(0)));
            }
        },
        e -> LoadingManager.showLoading(getContext(), false),
        () -> LoadingManager.showLoading(getContext(), false));
    }
}
