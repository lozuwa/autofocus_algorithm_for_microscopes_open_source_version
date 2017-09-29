package koko.videoreproducer;

import android.net.Uri;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.VideoView;

public class MainActivity extends AppCompatActivity {

    VideoView videoview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        /** Instantiate UI components */
        videoview = (VideoView) findViewById(R.id.videoview);
        videoview.setVideoURI(Uri.parse("https://www.youtube.com/watch?v=0bbzDgGpI5U"));
                //"android.resource://" + getPackageName() + "/" + R.raw.vid2));
        videoview.start();
    }

}
