package com.privacy.browser.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import com.privacy.browser.R;
import com.privacy.browser.media.MediaSniffer;
import com.privacy.downloader.engine.StreamDetector;
import com.privacy.downloader.ui.DownloadPromptDialog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * AMOLED bottom sheet dialog displaying available video qualities and companion audio tracks
 * with live combined file size estimates, format badges (AV1, H.264, VP9, AAC, Opus),
 * scrollable options with permanently pinned bottom action buttons.
 */
public class VideoQualityDialog {

    public interface OnLinkGrabberClickListener {
        void onOpenLinkGrabber();
    }

    public interface OnRefreshClickListener {
        void onRefresh();
    }

    public static void show(Context context, List<MediaSniffer.SniffedMedia> mediaList, String pageUrl, String userAgent, OnLinkGrabberClickListener grabberListener) {
        show(context, mediaList, pageUrl, userAgent, grabberListener, null);
    }

    public static void show(Context context, List<MediaSniffer.SniffedMedia> mediaList, String pageUrl, String userAgent, OnLinkGrabberClickListener grabberListener, OnRefreshClickListener refreshListener) {
        if (context == null || mediaList == null || mediaList.isEmpty()) {
            Toast.makeText(context, "No media streams detected yet", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        View root = LayoutInflater.from(context).inflate(R.layout.dialog_video_quality_picker, null);
        builder.setView(root);

        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            Window window = dialog.getWindow();
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
        }

        TextView tvTitle = root.findViewById(R.id.dialog_video_title);
        TextView tvSubtitle = root.findViewById(R.id.dialog_video_subtitle);
        ImageView btnRefresh = root.findViewById(R.id.dialog_btn_refresh);
        ImageView btnClose = root.findViewById(R.id.dialog_btn_close);

        View videoHeader = root.findViewById(R.id.dialog_section_video_header);
        TextView videoCountBadge = root.findViewById(R.id.dialog_video_count_badge);
        LinearLayout videoContainer = root.findViewById(R.id.dialog_quality_container);

        View audioHeader = root.findViewById(R.id.dialog_section_audio_header);
        TextView audioCountBadge = root.findViewById(R.id.dialog_audio_count_badge);
        LinearLayout audioContainer = root.findViewById(R.id.dialog_audio_container);

        Button btnDownload = root.findViewById(R.id.dialog_btn_download);
        TextView btnLinkGrabber = root.findViewById(R.id.dialog_btn_link_grabber);

        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(v -> {
                dialog.dismiss();
                if (refreshListener != null) {
                    refreshListener.onRefresh();
                }
            });
        }
        btnClose.setOnClickListener(v -> dialog.dismiss());

        // Separate media into video tracks and audio tracks
        List<MediaSniffer.SniffedMedia> videoList = new ArrayList<>();
        List<MediaSniffer.SniffedMedia> audioList = new ArrayList<>();
        for (MediaSniffer.SniffedMedia sm : mediaList) {
            if ("audio".equalsIgnoreCase(sm.getType())) {
                audioList.add(sm);
            } else {
                videoList.add(sm);
            }
        }

        // Sort videos: Highest quality first (e.g. 2160p > 1440p > 1080p > 720p), then highest bitrate
        Collections.sort(videoList, (a, b) -> {
            int scoreA = getQualityScore(a.getQuality());
            int scoreB = getQualityScore(b.getQuality());
            if (scoreA != scoreB) {
                return Integer.compare(scoreB, scoreA);
            }
            return Long.compare(b.getBitrate(), a.getBitrate());
        });

        // Sort audios: Highest bitrate first
        Collections.sort(audioList, (a, b) -> Long.compare(b.getBitrate(), a.getBitrate()));

        // Determine title
        String title = "Media Download";
        if (!videoList.isEmpty()) {
            title = videoList.get(0).getTitle();
        } else if (!audioList.isEmpty()) {
            title = audioList.get(0).getTitle();
        }
        if (title.contains(" (")) {
            title = title.substring(0, title.lastIndexOf(" (")).trim();
        }
        tvTitle.setText(title);

        final int[] selectedVideoIdx = new int[]{videoList.isEmpty() ? -1 : 0};
        final int[] selectedAudioIdx = new int[]{audioList.isEmpty() ? -1 : 0};

        final List<View> videoViews = new ArrayList<>();
        final List<RadioButton> videoRadios = new ArrayList<>();

        final List<View> audioViews = new ArrayList<>();
        final List<RadioButton> audioRadios = new ArrayList<>();

        // Helper to update selection UI and combined size
        Runnable updateUIState = () -> {
            MediaSniffer.SniffedMedia curVideo = (selectedVideoIdx[0] >= 0 && selectedVideoIdx[0] < videoList.size())
                    ? videoList.get(selectedVideoIdx[0]) : null;
            MediaSniffer.SniffedMedia curAudio = (selectedAudioIdx[0] >= 0 && selectedAudioIdx[0] < audioList.size())
                    ? audioList.get(selectedAudioIdx[0]) : null;

            if (curVideo != null) {
                long totalSize = curVideo.getFileSize();
                boolean needsAudio = (curVideo.getAudioUrl() != null || (curVideo.getUrl() != null && curVideo.getUrl().contains("videoplayback")));
                if (needsAudio && curAudio != null && curAudio.getFileSize() > 0) {
                    totalSize = (totalSize > 0) ? (totalSize + curAudio.getFileSize()) : curAudio.getFileSize();
                }

                String sizeStr = formatBytes(totalSize);
                btnDownload.setText("DOWNLOAD VIDEO" + (sizeStr.isEmpty() ? "" : " • ~" + sizeStr));

                StringBuilder sub = new StringBuilder();
                sub.append(curVideo.getQuality());
                if (curVideo.getCodec() != null && !curVideo.getCodec().isEmpty()) {
                    sub.append(" (").append(curVideo.getCodec()).append(")");
                }
                if (needsAudio && curAudio != null) {
                    sub.append(" + ").append(curAudio.getQuality());
                    if (curAudio.getCodec() != null) {
                        sub.append(" (").append(curAudio.getCodec()).append(")");
                    }
                    sub.append(" • Merged MP4");
                }
                tvSubtitle.setText(sub.toString());
            } else if (curAudio != null) {
                String sizeStr = formatBytes(curAudio.getFileSize());
                btnDownload.setText("DOWNLOAD AUDIO" + (sizeStr.isEmpty() ? "" : " • ~" + sizeStr));
                tvSubtitle.setText(curAudio.getQuality() + (curAudio.getCodec() != null ? " (" + curAudio.getCodec() + ")" : "") + " • Audio Only");
            } else {
                btnDownload.setText("SELECT FORMAT");
                tvSubtitle.setText("Choose video and audio formats above");
            }
        };

        // Populate Video Container
        if (!videoList.isEmpty()) {
            videoHeader.setVisibility(View.VISIBLE);
            videoCountBadge.setText(videoList.size() + " Option" + (videoList.size() > 1 ? "s" : ""));

            for (int i = 0; i < videoList.size(); i++) {
                final int index = i;
                MediaSniffer.SniffedMedia sm = videoList.get(i);
                View itemView = LayoutInflater.from(context).inflate(R.layout.item_video_quality, videoContainer, false);

                RadioButton rb = itemView.findViewById(R.id.item_quality_radio);
                TextView label = itemView.findViewById(R.id.item_quality_label);
                TextView desc = itemView.findViewById(R.id.item_quality_desc);
                TextView sizeView = itemView.findViewById(R.id.item_quality_size);

                label.setText(formatQualityLabel(sm.getQuality(), sm.getCodec()));
                desc.setText(sm.getDisplayDescription());
                sizeView.setText(sm.getFormattedSize());

                rb.setChecked(i == selectedVideoIdx[0]);
                itemView.setSelected(i == selectedVideoIdx[0]);

                itemView.setOnClickListener(v -> {
                    selectedVideoIdx[0] = index;
                    for (int j = 0; j < videoRadios.size(); j++) {
                        videoRadios.get(j).setChecked(j == index);
                        videoViews.get(j).setSelected(j == index);
                    }
                    updateUIState.run();
                });

                videoRadios.add(rb);
                videoViews.add(itemView);
                videoContainer.addView(itemView);
            }
        } else {
            videoHeader.setVisibility(View.GONE);
            videoContainer.setVisibility(View.GONE);
        }

        // Populate Audio Container
        if (!audioList.isEmpty()) {
            audioHeader.setVisibility(View.VISIBLE);
            audioCountBadge.setText(audioList.size() + " Track" + (audioList.size() > 1 ? "s" : ""));

            for (int i = 0; i < audioList.size(); i++) {
                final int index = i;
                MediaSniffer.SniffedMedia sm = audioList.get(i);
                View itemView = LayoutInflater.from(context).inflate(R.layout.item_video_quality, audioContainer, false);

                RadioButton rb = itemView.findViewById(R.id.item_quality_radio);
                TextView label = itemView.findViewById(R.id.item_quality_label);
                TextView desc = itemView.findViewById(R.id.item_quality_desc);
                TextView sizeView = itemView.findViewById(R.id.item_quality_size);

                String audioCodec = sm.getCodec() != null ? sm.getCodec() : "Audio";
                label.setText(sm.getQuality() + " (" + audioCodec + ")");
                desc.setText(audioCodec + " • High Fidelity Audio Track");
                sizeView.setText(sm.getFormattedSize());

                rb.setChecked(i == selectedAudioIdx[0]);
                itemView.setSelected(i == selectedAudioIdx[0]);

                itemView.setOnClickListener(v -> {
                    selectedAudioIdx[0] = index;
                    for (int j = 0; j < audioRadios.size(); j++) {
                        audioRadios.get(j).setChecked(j == index);
                        audioViews.get(j).setSelected(j == index);
                    }
                    updateUIState.run();
                });

                audioRadios.add(rb);
                audioViews.add(itemView);
                audioContainer.addView(itemView);
            }
        } else {
            audioHeader.setVisibility(View.GONE);
            audioContainer.setVisibility(View.GONE);
        }

        // Initial UI update
        updateUIState.run();

        final String finalTitle = title;
        btnDownload.setOnClickListener(v -> {
            MediaSniffer.SniffedMedia selectedVideo = (selectedVideoIdx[0] >= 0 && selectedVideoIdx[0] < videoList.size())
                    ? videoList.get(selectedVideoIdx[0]) : null;
            MediaSniffer.SniffedMedia selectedAudio = (selectedAudioIdx[0] >= 0 && selectedAudioIdx[0] < audioList.size())
                    ? audioList.get(selectedAudioIdx[0]) : null;

            if (selectedVideo == null && selectedAudio == null) {
                Toast.makeText(context, "Please select a format to download", Toast.LENGTH_SHORT).show();
                return;
            }

            dialog.dismiss();

            if (selectedVideo != null) {
                String videoUrl = selectedVideo.getUrl();
                boolean isStream = StreamDetector.isStream(videoUrl, null) || videoUrl.contains(".m3u8") || videoUrl.contains(".mpd");
                String mimeType = isStream ? "application/vnd.apple.mpegurl" : "video/mp4";

                boolean isGoogleVideo = videoUrl.contains("googlevideo.com");
                String effectiveCookies = CookieManager.getInstance().getCookie(pageUrl);
                if (effectiveCookies == null && isGoogleVideo) {
                    effectiveCookies = CookieManager.getInstance().getCookie("https://youtube.com");
                }
                String effectiveReferrer;
                if (isGoogleVideo) {
                    effectiveReferrer = (pageUrl != null && !pageUrl.isEmpty() ? pageUrl : "https://m.youtube.com/");
                } else {
                    effectiveReferrer = (selectedVideo.getReferrer() != null ? selectedVideo.getReferrer() : pageUrl);
                }

                // Companion audio track resolution
                String audioUrl = null;
                long audioBytes = -1;
                if (selectedAudio != null) {
                    audioUrl = selectedAudio.getUrl();
                    audioBytes = selectedAudio.getFileSize();
                } else if (selectedVideo.getAudioUrl() != null) {
                    audioUrl = selectedVideo.getAudioUrl();
                    audioBytes = selectedVideo.getAudioFileSize();
                }

                long totalBytes = selectedVideo.getFileSize();
                if (audioUrl != null && audioBytes > 0) {
                    totalBytes = (totalBytes > 0) ? (totalBytes + audioBytes) : audioBytes;
                }

                String codecSuffix = (selectedVideo.getCodec() != null && !selectedVideo.getCodec().isEmpty())
                        ? "_" + selectedVideo.getCodec() : "";
                String rawFileName = finalTitle + "_" + selectedVideo.getQuality() + codecSuffix;
                String cleanName = rawFileName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
                String mp4FileName = StreamDetector.ensureMp4Extension(cleanName);

                DownloadPromptDialog.show(context, videoUrl, userAgent, null, mimeType,
                        effectiveCookies, effectiveReferrer, mp4FileName, totalBytes, audioUrl, audioBytes, null);
            } else {
                // Audio Only download
                String audioUrl = selectedAudio.getUrl();
                boolean isGoogleVideo = audioUrl.contains("googlevideo.com");
                String effectiveCookies = CookieManager.getInstance().getCookie(pageUrl);
                if (effectiveCookies == null && isGoogleVideo) {
                    effectiveCookies = CookieManager.getInstance().getCookie("https://youtube.com");
                }
                String effectiveReferrer = isGoogleVideo ? "https://m.youtube.com/" : (selectedAudio.getReferrer() != null ? selectedAudio.getReferrer() : pageUrl);

                String ext = "AAC".equalsIgnoreCase(selectedAudio.getCodec()) ? ".m4a" : ".opus";
                String rawFileName = finalTitle + "_Audio_" + selectedAudio.getQuality();
                String cleanName = rawFileName.replaceAll("[\\\\/:*?\"<>|]", "_").trim() + ext;

                DownloadPromptDialog.show(context, audioUrl, userAgent, null, "audio/*",
                        effectiveCookies, effectiveReferrer, cleanName, selectedAudio.getFileSize(), null, -1, null);
            }
        });

        if (grabberListener != null) {
            btnLinkGrabber.setVisibility(View.VISIBLE);
            btnLinkGrabber.setOnClickListener(v -> {
                dialog.dismiss();
                grabberListener.onOpenLinkGrabber();
            });
        } else {
            btnLinkGrabber.setVisibility(View.GONE);
        }

        dialog.show();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

            // Constrain root height on tall lists so bottom action button is never pushed off screen
            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            int maxDialogHeight = (int) (dm.heightPixels * 0.82);
            root.post(() -> {
                if (root.getHeight() > maxDialogHeight) {
                    ViewGroup.LayoutParams lp = root.getLayoutParams();
                    if (lp != null) {
                        lp.height = maxDialogHeight;
                        root.setLayoutParams(lp);
                    }
                }
            });
        }
    }

    private static String formatQualityLabel(String quality, String codec) {
        String base = formatQualityLabel(quality);
        if (codec != null && !codec.isEmpty()) {
            return base + " (" + codec + ")";
        }
        return base;
    }

    private static String formatQualityLabel(String quality) {
        if (quality == null) return "Video";
        String q = quality.toLowerCase(Locale.US);
        if (q.contains("2160") || q.contains("4k")) return "2160p 4K Ultra HD";
        if (q.contains("1440") || q.contains("2k")) return "1440p 2K Quad HD";
        if (q.contains("1080")) return "1080p Full HD";
        if (q.contains("720")) return "720p HD";
        if (q.contains("540")) return "540p Standard";
        if (q.contains("480")) return "480p SD";
        if (q.contains("360")) return "360p Mobile";
        if (q.contains("240")) return "240p Low Data";
        if (q.contains("144")) return "144p Data Saver";
        if ("Auto".equalsIgnoreCase(quality) || "HLS Master".equalsIgnoreCase(quality)) return "Auto Quality (HLS Master)";
        return quality + " Video";
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "";
        final String[] units = new String[]{"B", "KB", "MB", "GB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        if (digitGroups >= units.length) digitGroups = units.length - 1;
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    private static int getQualityScore(String quality) {
        if (quality == null) return 0;
        String q = quality.toLowerCase(Locale.US);
        if (q.contains("2160") || q.contains("4k")) return 2160;
        if (q.contains("1440") || q.contains("2k")) return 1440;
        if (q.contains("1080")) return 1080;
        if (q.contains("720")) return 720;
        if (q.contains("540")) return 540;
        if (q.contains("480")) return 480;
        if (q.contains("360")) return 360;
        if (q.contains("240")) return 240;
        if (q.contains("144")) return 144;
        if (q.contains("auto") || q.contains("hls")) return 100;
        return 50;
    }
}
