# Chroma Key Video View
Chroma Key Video View is a video player which can change set solid backgrounds to transparent and change 
all other colors in the video to any color of your choosing. Additionally, it also has fade in, fade out, 
video scaling and video centering features.

## Gradle dependency
The Gradle dependency is provided by JitPack. 
Add the following repository to the project's build.gradle file

    allprojects {
    repositories {
        maven { url 'https://jitpack.io' }
    }
    }
    
Finally, add this dependency to your module's build.gradle file:

    implementation 'com.github.kalherath:chromakeyvideoview:1.0.0'
    
## How to use it
Add a ChromaKeyVideoView inside a layout xml.
This is an example with all the unique parameters for this view:

  ```
  <com.chroma.view.ChromaKeyVideoView
        android:id="@+id/video_view"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        custom:videoScale="0.9"
        custom:centerVideoX="0.5"
        custom:centerVideoY="0.5"
        custom:silhouetteMode="true"
        custom:backgroundColor="#00ff00"
        custom:silhouetteColor="#000000"
        custom:tolerance="0.3"
        custom:looping="true"
        custom:fadeInAtStart="true"
        custom:fadeOutTowardsEnd="true"
        custom:fadeInDuration="200"
        custom:fadeInDelay="500"
        custom:fadeOutDuration="200"
        custom:fadeOutLead="500"
        android:visibility="visible"/> 
  ```
   
Then set up and use the view with a video in an Activity or Fragment:

```
  class VideoFragment : Fragment(), ChromaKeyVideoView.OnVideoStartedListener {

    private lateinit var videoView : ChromaKeyVideoView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.video_fragment, container, false)
        videoView = view.findViewById(R.id.video_view)
        videoView.setOnVideoStartedListener(this)
        val packageName : String? = context?.packageName
        packageName?.let {
            videoView.setVideoFromUri(Uri.parse("android.resource://" + it + "/raw/myvideo"))
        }
        return view
    }

    override fun onResume() {
        super.onResume()
        videoView.onResume() //always call this during onResume
    }

    override fun onPause() {
        videoView.onPause() //always call this during onPause
        super.onPause()
    }

    override fun onVideoStarted() {
      //things to do once the video starts
    }
}
```
There are several ways to set the video source using setVideoFrom... methods.

## Credits

Thanks to [Pavel Semak](https://github.com/pavelsemak) and his library [Alpha Movie](https://github.com/pavelsemak/alpha-movie)
on which Chroma Key Video View is based.


