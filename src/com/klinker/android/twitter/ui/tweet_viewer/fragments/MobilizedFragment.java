package com.klinker.android.twitter.ui.tweet_viewer.fragments;

import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;

import com.klinker.android.twitter.R;
import com.klinker.android.twitter.manipulations.widgets.HoloTextView;
import com.klinker.android.twitter.settings.AppSettings;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

import java.net.URL;
import java.util.ArrayList;

/**
 * Created by luke on 4/1/14.
 */
public class MobilizedFragment extends Fragment {

    private ArrayList<String> webpages;

    private View layout;
    private HoloTextView webText;
    private ScrollView scrollView;
    private LinearLayout spinner;

    public Context context;
    public AppSettings settings;

    public MobilizedFragment(AppSettings settings, ArrayList<String> webpages) {
        this.webpages = webpages;
        this.settings = settings;
    }

    public MobilizedFragment() {
        this.webpages = new ArrayList<String>();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);

        context = getActivity();

        layout = inflater.inflate(R.layout.mobilized_fragment, null, false);
        webText = (HoloTextView) layout.findViewById(R.id.webpage_text);
        scrollView = (ScrollView) layout.findViewById(R.id.scrollview);
        spinner = (LinearLayout) layout.findViewById(R.id.spinner);

        getTextFromSite();

        return layout;
    }

    public boolean isStopped = false;

    @Override
    public void onStop() {
        isStopped = true;
        super.onStop();
    }

    public void getTextFromSite() {
        Thread getText = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Document doc = Jsoup.connect(webpages.get(0)).get();

                    String text = "";
                    String title = doc.title();

                    if(doc != null) {
                        Elements paragraphs = doc.getElementsByTag("p");

                        if (paragraphs.hasText()) {
                            text = paragraphs.html();
                        }
                    }

                    String noImages = "";

                    for (String s : text.split(" ")) {
                        if (!s.contains("<img")) {
                            noImages += s;
                        }
                    }

                    final String article =
                            "<strong><big>" + title + "</big></strong>" +
                            "<br/><br/>" +
                             text.replaceAll("<img.+?>", "");

                    ((Activity)context).runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            webText.setText(Html.fromHtml(article.replaceAll("\\n\\n\\n", "<br/><br/>")
                                    .replaceAll("\\n\\n", "<br/><br/>")
                                    .replaceAll("\\n", "<br/><br/>")));
                            webText.setMovementMethod(LinkMovementMethod.getInstance());
                            webText.setTextSize(settings.textSize);

                            spinner.setVisibility(View.GONE);
                            webText.setVisibility(View.VISIBLE);
                        }
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                    ((Activity)context).runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            webText.setText(getResources().getString(R.string.error_loading_page));
                        }
                    });
                }
            }
        });

        getText.setPriority(8);
        getText.start();
    }
}