/***
 * Copyright (c) 2015-2016 CommonsWare, LLC
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.commonsware.cwac.cam2;

import android.app.ActionBar;
import android.app.Fragment;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.LayoutRes;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.github.clans.fab.FloatingActionButton;

import java.util.LinkedList;

import de.greenrobot.event.EventBus;

/**
 * Fragment for displaying a camera preview, with hooks to allow
 * you (or the user) to take a picture.
 */
public class CameraFragment extends Fragment {

	private static final String ARG_OUTPUT = "output";
	private static final String ARG_UPDATE_MEDIA_STORE = "updateMediaStore";
	private static final String ARG_SKIP_ORIENTATION_NORMALIZATION = "skipOrientationNormalization";
	private static final String ARG_QUALITY = "quality";
	private static final String ARG_FACING_EXACT_MATCH = "facingExactMatch";
	private static final String ARG_CUSTOM_LAYOUT = "customLayout";
	private CameraController controller;
	private ViewGroup previewStack;
	private FloatingActionButton fabSwitch;
	private View progress;
	private boolean mirrorPreview = false;

	public static CameraFragment newPictureInstance(Uri output,
													boolean updateMediaStore,
													int quality,
													boolean facingExactMatch,
													boolean skipOrientationNormalization,
													@LayoutRes int customLayout) {
		CameraFragment f = new CameraFragment();
		Bundle args = new Bundle();

		args.putParcelable(ARG_OUTPUT, output);
		args.putBoolean(ARG_UPDATE_MEDIA_STORE, updateMediaStore);
		args.putBoolean(ARG_SKIP_ORIENTATION_NORMALIZATION, skipOrientationNormalization);
		args.putInt(ARG_QUALITY, quality);
		args.putBoolean(ARG_FACING_EXACT_MATCH, facingExactMatch);
		args.putInt(ARG_CUSTOM_LAYOUT, customLayout);
		f.setArguments(args);

		return f;
	}

	/**
	 * Standard fragment entry point.
	 *
	 * @param savedInstanceState State of a previous instance
	 */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setRetainInstance(true);
	}

	/**
	 * Standard lifecycle method, passed along to the CameraController.
	 */
	@Override
	public void onStart() {
		super.onStart();

		EventBus.getDefault().register(this);

		if (controller != null) {
			controller.start();
		}
	}

	@Override
	public void onHiddenChanged(boolean isHidden) {
		super.onHiddenChanged(isHidden);

		if (!isHidden) {
			ActionBar ab = getActivity().getActionBar();

			if (ab != null) {
				ab.setBackgroundDrawable(getActivity()
						.getResources()
						.getDrawable(R.drawable.cwac_cam2_action_bar_bg_transparent));
				ab.setTitle("");

				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
					ab.setDisplayHomeAsUpEnabled(false);
				} else {
					ab.setDisplayShowHomeEnabled(false);
					ab.setHomeButtonEnabled(false);
				}
			}
		}
	}

	/**
	 * Standard lifecycle method, for when the fragment moves into
	 * the stopped state. Passed along to the CameraController.
	 */
	@Override
	public void onStop() {
		if (controller != null) {
			try {
				controller.stop();
			} catch (Exception e) {
				controller.postError(ErrorConstants.ERROR_STOPPING, e);
				Log.e(getClass().getSimpleName(), "Exception stopping controller", e);
			}
		}

		EventBus.getDefault().unregister(this);

		super.onStop();
	}

	/**
	 * Standard lifecycle method, for when the fragment is utterly,
	 * ruthlessly destroyed. Passed along to the CameraController,
	 * because why should the fragment have all the fun?
	 */
	@Override
	public void onDestroy() {
		if (controller != null) {
			controller.destroy();
		}

		super.onDestroy();
	}

	/**
	 * Standard callback method to create the UI managed by
	 * this fragment.
	 *
	 * @param inflater           Used to inflate layouts
	 * @param container          Parent of the fragment's UI (eventually)
	 * @param savedInstanceState State of a previous instance
	 * @return the UI being managed by this fragment
	 */
	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		int layout = getArguments().getInt(ARG_CUSTOM_LAYOUT);
		View v = inflater.inflate(layout != 0 ? layout : R.layout.cwac_cam2_fragment, container, false);

		previewStack = (ViewGroup) v.findViewById(R.id.cwac_cam2_preview_stack);

		progress = v.findViewById(R.id.cwac_cam2_progress);

		fabSwitch = (FloatingActionButton) v.findViewById(R.id.cwac_cam2_switch_camera);
		fabSwitch.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				progress.setVisibility(View.VISIBLE);
				fabSwitch.setEnabled(false);

				try {
					controller.switchCamera();
				} catch (Exception e) {
					controller.postError(ErrorConstants.ERROR_SWITCHING_CAMERAS, e);
					Log.e(getClass().getSimpleName(), "Exception switching camera", e);
				}
			}
		});

		CameraView cameraView = (CameraView) v.findViewById(R.id.cwac_cam2_camera_view);
		cameraView.setOnClickListener(new View.OnClickListener() {
			@Override public void onClick(View view) {
				takePicture();
			}
		});

		onHiddenChanged(false); // hack, since this does not get
		// called on initial display

		fabSwitch.setEnabled(false);

		if (controller != null && controller.getNumberOfCameras() > 0) {
			prepController();
		}

		return (v);
	}

	public void shutdown() {
		progress.setVisibility(View.VISIBLE);

		if (controller != null) {
			try {
				controller.stop();
			} catch (Exception e) {
				controller.postError(ErrorConstants.ERROR_STOPPING, e);
				Log.e(getClass().getSimpleName(), "Exception stopping controller", e);
			}
		}
	}

	/**
	 * Establishes the controller that this fragment delegates to
	 *
	 * @param ctlr the controller that this fragment delegates to
	 */
	public void setController(CameraController ctlr) {
		int currentCamera = -1;

		if (this.controller != null) {
			currentCamera = this.controller.getCurrentCamera();
		}

		this.controller = ctlr;
		ctlr.setQuality(getArguments().getInt(ARG_QUALITY, 1));

		if (currentCamera > -1) {
			ctlr.setCurrentCamera(currentCamera);
		}
	}

	/**
	 * Indicates if we should mirror the preview or not. Defaults
	 * to false.
	 *
	 * @param mirror true if we should horizontally mirror the
	 *               preview, false otherwise
	 */
	public void setMirrorPreview(boolean mirror) {
		this.mirrorPreview = mirror;
	}

	@SuppressWarnings("unused")
	public void onEventMainThread(CameraController.ControllerReadyEvent event) {
		if (event.isEventForController(controller)) {
			prepController();
		}
	}

	@SuppressWarnings("unused")
	public void onEventMainThread(CameraEngine.OpenedEvent event) {
		if (event.exception == null) {
			progress.setVisibility(View.GONE);
			fabSwitch.setEnabled(canSwitchSources());
			previewStack.setOnTouchListener(null);
		} else {
			controller.postError(ErrorConstants.ERROR_OPEN_CAMERA, event.exception);
			getActivity().finish();
		}
	}

	private void takePicture() {
		Uri output = getArguments().getParcelable(ARG_OUTPUT);

		PictureTransaction.Builder b = new PictureTransaction.Builder();

		if (output != null) {
			b.toUri(getActivity(), output,
					getArguments().getBoolean(ARG_UPDATE_MEDIA_STORE, false),
					getArguments().getBoolean(ARG_SKIP_ORIENTATION_NORMALIZATION, false));
		}

		fabSwitch.setEnabled(false);
		controller.takePicture(b.build());
	}

	private boolean canSwitchSources() {
		return !getArguments().getBoolean(ARG_FACING_EXACT_MATCH, false);
	}

	private void prepController() {
		LinkedList<CameraView> cameraViews = new LinkedList<>();
		CameraView cv = (CameraView) previewStack.getChildAt(0);

		cv.setMirror(mirrorPreview);
		cameraViews.add(cv);

		for (int i = 1; i < controller.getNumberOfCameras(); i++) {
			cv = new CameraView(getActivity());
			cv.setVisibility(View.INVISIBLE);
			cv.setMirror(mirrorPreview);
			previewStack.addView(cv);
			cameraViews.add(cv);
		}

		controller.setCameraViews(cameraViews);
	}
}