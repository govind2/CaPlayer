package com.balivo.caplayer

import android.annotation.TargetApi
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.FrameLayout

import org.videolan.libvlc.IVLCVout
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

import java.util.ArrayList

class JavaActivity : AppCompatActivity(), IVLCVout.OnNewVideoLayoutListener {

    private var mVideoSurfaceFrame: FrameLayout? = null
    private var mVideoSurface: SurfaceView? = null
    private var mSubtitlesSurface: SurfaceView? = null
    private var mVideoTexture: TextureView? = null
    private var mVideoView: View? = null

    private val mHandler = Handler()
    private var mOnLayoutChangeListener: View.OnLayoutChangeListener? = null

    private var mLibVLC: LibVLC? = null
    private var mMediaPlayer: MediaPlayer? = null
    private var mVideoHeight = 0
    private var mVideoWidth = 0
    private var mVideoVisibleHeight = 0
    private var mVideoVisibleWidth = 0
    private var mVideoSarNum = 0
    private var mVideoSarDen = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        val args = ArrayList<String>()
        args.add("-vvv")
        //args.add("--file-caching=10000")
        //args.add("--http-caching=30000")
        //args.add("")
        //args.add("--rtsp-caching=2600")
        //args.add("--gnutls-cache-size=30000")
        //args.add("--udp-caching=3000")
        //args.add("--sout-mux-caching=15000")
        mLibVLC = LibVLC(this, args)
        mMediaPlayer = MediaPlayer(mLibVLC)

        mVideoSurfaceFrame = findViewById<FrameLayout>(R.id.video_surface_frame)
        if (USE_SURFACE_VIEW) {
            var stub = findViewById<ViewStub>(R.id.surface_stub)
            mVideoSurface = stub.inflate() as SurfaceView
            if (ENABLE_SUBTITLES) {
                stub = findViewById<ViewStub>(R.id.subtitles_surface_stub)
                mSubtitlesSurface = stub.inflate() as SurfaceView
                mSubtitlesSurface!!.setZOrderMediaOverlay(true)
                mSubtitlesSurface!!.holder.setFormat(PixelFormat.TRANSLUCENT)
            }
            mVideoView = mVideoSurface
        } else {
            val stub = findViewById<ViewStub>(R.id.texture_stub)
            mVideoTexture = stub.inflate() as TextureView
            mVideoView = mVideoTexture
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mMediaPlayer!!.release()
        mLibVLC!!.release()
    }

    override fun onStart() {
        super.onStart()

        val vlcVout = mMediaPlayer!!.vlcVout
        if (mVideoSurface != null) {
            vlcVout.setVideoView(mVideoSurface)
            if (mSubtitlesSurface != null)
                vlcVout.setSubtitlesView(mSubtitlesSurface)
        } else
            vlcVout.setVideoView(mVideoTexture)
        vlcVout.attachViews(this)

        val media = Media(mLibVLC, Uri.parse(SAMPLE_URL))
        mMediaPlayer!!.media = media
        media.release()
        mMediaPlayer!!.play()

        if (mOnLayoutChangeListener == null) {
            mOnLayoutChangeListener = object : View.OnLayoutChangeListener {
                private val mRunnable = Runnable { updateVideoSurfaces() }
                override fun onLayoutChange(v: View, left: Int, top: Int, right: Int,
                                            bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                    if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                        mHandler.removeCallbacks(mRunnable)
                        mHandler.post(mRunnable)
                    }
                }
            }
        }
        mVideoSurfaceFrame!!.addOnLayoutChangeListener(mOnLayoutChangeListener)
    }

    override fun onStop() {
        super.onStop()

        if (mOnLayoutChangeListener != null) {
            mVideoSurfaceFrame!!.removeOnLayoutChangeListener(mOnLayoutChangeListener)
            mOnLayoutChangeListener = null
        }

        mMediaPlayer!!.stop()

        mMediaPlayer!!.vlcVout.detachViews()
    }

    private fun changeMediaPlayerLayout(displayW: Int, displayH: Int) {
        /* Change the video placement using the MediaPlayer API */
        when (CURRENT_SIZE) {
            SURFACE_BEST_FIT -> {
                mMediaPlayer!!.aspectRatio = null
                mMediaPlayer!!.scale = 0F
            }
            SURFACE_FIT_SCREEN, SURFACE_FILL -> {
                val vtrack = mMediaPlayer!!.currentVideoTrack ?: return
                val videoSwapped = vtrack.orientation === Media.VideoTrack.Orientation.LeftBottom || vtrack.orientation === Media.VideoTrack.Orientation.RightTop
                if (CURRENT_SIZE == SURFACE_FIT_SCREEN) {
                    var videoW = vtrack.width
                    var videoH = vtrack.height

                    if (videoSwapped) {
                        val swap = videoW
                        videoW = videoH
                        videoH = swap
                    }
                    if (vtrack.sarNum !== vtrack.sarDen)
                        videoW = videoW * vtrack.sarNum / vtrack.sarDen

                    val ar = videoW / videoH.toFloat()
                    val dar = displayW / displayH.toFloat()

                    val scale: Float
                    if (dar >= ar)
                        scale = displayW / videoW.toFloat() /* horizontal */
                    else
                        scale = displayH / videoH.toFloat() /* vertical */
                    mMediaPlayer!!.scale = scale
                    mMediaPlayer!!.aspectRatio = null
                } else {
                    mMediaPlayer!!.setScale(0F)
                    mMediaPlayer!!.aspectRatio = if (!videoSwapped)
                        "" + displayW + ":" + displayH
                    else
                        "" + displayH + ":" + displayW
                }
            }
            SURFACE_16_9 -> {
                mMediaPlayer!!.aspectRatio = "16:9"
                mMediaPlayer!!.setScale(0F)
            }
            SURFACE_4_3 -> {
                mMediaPlayer!!.aspectRatio = "4:3"
                mMediaPlayer!!.setScale(0F)
            }
            SURFACE_ORIGINAL -> {
                mMediaPlayer!!.aspectRatio = null
                mMediaPlayer!!.setScale(1F)
            }
        }
    }

    private fun updateVideoSurfaces() {
        val sw = window.decorView.width
        val sh = window.decorView.height

        // sanity check
        if (sw * sh == 0) {
            Log.e(TAG, "Invalid surface size")
            return
        }

        mMediaPlayer!!.vlcVout.setWindowSize(sw, sh)

        var lp = mVideoView!!.layoutParams
        if (mVideoWidth * mVideoHeight == 0) {
            /* Case of OpenGL vouts: handles the placement of the video using MediaPlayer API */
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            mVideoView!!.layoutParams = lp
            lp = mVideoSurfaceFrame!!.layoutParams
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            mVideoSurfaceFrame!!.layoutParams = lp
            changeMediaPlayerLayout(sw, sh)
            return
        }

        if (lp.width == lp.height && lp.width == ViewGroup.LayoutParams.MATCH_PARENT) {
            /* We handle the placement of the video using Android View LayoutParams */
            mMediaPlayer!!.aspectRatio = null
            mMediaPlayer!!.setScale(0F)
        }

        var dw = sw.toDouble()
        var dh = sh.toDouble()
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

        if (sw > sh && isPortrait || sw < sh && !isPortrait) {
            dw = sh.toDouble()
            dh = sw.toDouble()
        }

        // compute the aspect ratio
        var ar: Double
        val vw: Double
        if (mVideoSarDen == mVideoSarNum) {
            /* No indication about the density, assuming 1:1 */
            vw = mVideoVisibleWidth.toDouble()
            ar = mVideoVisibleWidth.toDouble() / mVideoVisibleHeight.toDouble()
        } else {
            /* Use the specified aspect ratio */
            vw = mVideoVisibleWidth * mVideoSarNum.toDouble() / mVideoSarDen
            ar = vw / mVideoVisibleHeight
        }

        // compute the display aspect ratio
        val dar = dw / dh

        when (CURRENT_SIZE) {
            SURFACE_BEST_FIT -> if (dar < ar)
                dh = dw / ar
            else
                dw = dh * ar
            SURFACE_FIT_SCREEN -> if (dar >= ar)
                dh = dw / ar /* horizontal */
            else
                dw = dh * ar /* vertical */
            SURFACE_FILL -> {
            }
            SURFACE_16_9 -> {
                ar = 16.0 / 9.0
                if (dar < ar)
                    dh = dw / ar
                else
                    dw = dh * ar
            }
            SURFACE_4_3 -> {
                ar = 4.0 / 3.0
                if (dar < ar)
                    dh = dw / ar
                else
                    dw = dh * ar
            }
            SURFACE_ORIGINAL -> {
                dh = mVideoVisibleHeight.toDouble()
                dw = vw
            }
        }

        // set display size
        lp.width = Math.ceil(dw * mVideoWidth / mVideoVisibleWidth).toInt()
        lp.height = Math.ceil(dh * mVideoHeight / mVideoVisibleHeight).toInt()
        mVideoView!!.layoutParams = lp
        if (mSubtitlesSurface != null)
            mSubtitlesSurface!!.layoutParams = lp

        // set frame size (crop if necessary)
        lp = mVideoSurfaceFrame!!.layoutParams
        lp.width = Math.floor(dw).toInt()
        lp.height = Math.floor(dh).toInt()
        mVideoSurfaceFrame!!.layoutParams = lp

        mVideoView!!.invalidate()
        if (mSubtitlesSurface != null)
            mSubtitlesSurface!!.invalidate()
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    override fun onNewVideoLayout(vlcVout: IVLCVout, width: Int, height: Int, visibleWidth: Int, visibleHeight: Int, sarNum: Int, sarDen: Int) {
        mVideoWidth = width
        mVideoHeight = height
        mVideoVisibleWidth = visibleWidth
        mVideoVisibleHeight = visibleHeight
        mVideoSarNum = sarNum
        mVideoSarDen = sarDen
        updateVideoSurfaces()
    }

    companion object {
        private val USE_SURFACE_VIEW = true
        private val ENABLE_SUBTITLES = true
        private val TAG = "JavaActivity"
        //private static final String SAMPLE_URL = "http://download.blender.org/peach/bigbuckbunny_movies/BigBuckBunny_640x360.m4v";
        private val SAMPLE_URL = "http://play.3mux.ml/555abc333abc/5232"
        private val SURFACE_BEST_FIT = 0
        private val SURFACE_FIT_SCREEN = 1
        private val SURFACE_FILL = 2
        private val SURFACE_16_9 = 3
        private val SURFACE_4_3 = 4
        private val SURFACE_ORIGINAL = 5
        private val CURRENT_SIZE = SURFACE_BEST_FIT
    }
}
