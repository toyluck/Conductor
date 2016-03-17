package com.bluelinelabs.conductor;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.support.annotation.NonNull;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnAttachStateChangeListener;
import android.view.ViewGroup;

import com.bluelinelabs.conductor.ControllerTransaction.ControllerChangeType;
import com.bluelinelabs.conductor.changehandler.SimpleSwapChangeHandler;
import com.bluelinelabs.conductor.util.ClassUtils;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * A Controller manages portions of the UI. It is similar to an Activity or Fragment in that it manages its
 * own lifecycle and controls interactions between the UI and whatever logic is required. It is, however,
 * a much lighter weight component than either Activities or Fragments. While it offers several lifecycle
 * methods, they are much simpler and more predictable than those of Activities and Fragments.
 */
public abstract class Controller {

    private static final String KEY_CLASS_NAME = "Controller.className";
    private static final String KEY_VIEW_STATE = "Controller.viewState";
    private static final String KEY_CHILDREN = "Controller.childControllers";
    private static final String KEY_SAVED_STATE = "Controller.savedState";
    private static final String KEY_INSTANCE_ID = "Controller.instanceId";
    private static final String KEY_TARGET_INSTANCE_ID = "Controller.target.instanceId";
    private static final String KEY_ARGS = "Controller.args";
    private static final String KEY_NEEDS_ATTACH = "Controller.needsAttach";
    private static final String KEY_REQUESTED_PERMISSIONS = "Controller.childControllers";
    private static final String KEY_OVERRIDDEN_PUSH_HANDLER = "Controller.overriddenPushHandler";
    private static final String KEY_OVERRIDDEN_POP_HANDLER = "Controller.overriddenPopHandler";
    private static final String KEY_VIEW_STATE_HIERARCHY = "Controller.viewState.hierarchy";
    private static final String KEY_VIEW_STATE_BUNDLE = "Controller.viewState.bundle";

    private final Bundle mArgs;

    private Bundle mViewState;
    private boolean mDestroyed;
    private boolean mAttached;
    private Router mRouter;
    private View mView;
    private Controller mParentController;
    private String mInstanceId;
    private String mTargetInstanceId;
    private boolean mNeedsAttach;
    private ControllerChangeHandler mOverriddenPushHandler;
    private ControllerChangeHandler mOverriddenPopHandler;
    private RetainViewMode mRetainViewMode = RetainViewMode.RELEASE_DETACH;
    private final List<ChildControllerTransaction> mChildControllers = new ArrayList<>();
    private final List<LifecycleListener> mLifecycleListeners = new ArrayList<>();
    private final ArrayList<String> mRequestedPermissions = new ArrayList<>();

    static Controller newInstance(Bundle bundle) {
        final String className = bundle.getString(KEY_CLASS_NAME);
        //noinspection ConstantConditions
        Constructor[] constructors = ClassUtils.classForName(className, false).getConstructors();
        Constructor bundleConstructor = getBundleConstructor(constructors);

        Controller controller;
        try {
            if (bundleConstructor != null) {
                controller = (Controller)bundleConstructor.newInstance(bundle.getBundle(KEY_ARGS));
            } else {
                //noinspection ConstantConditions
                controller = (Controller)getDefaultConstructor(constructors).newInstance();
            }
        } catch (Exception e) {
            throw new RuntimeException("An exception occurred while creating a new instance of " + className + ". " + e.getMessage());
        }

        controller.restoreInstanceState(bundle);
        return controller;
    }

    /**
     * Convenience constructor for use when no arguments are needed.
     */
    protected Controller() {
        this(null);
    }

    /**
     * Constructor that takes arguments that need to be retained across restarts.
     *
     * @param args Any arguments that need to be retained.
     */
    protected Controller(Bundle args) {
        mArgs = args;
        mInstanceId = UUID.randomUUID().toString();
        ensureRequiredConstructor();
    }

    /**
     * Called when the controller is ready to display its view. A valid view must be returned. The standard body
     * for this method will be {@code return inflater.inflate(R.layout.my_layout, container, false);}
     *
     * @param inflater The LayoutInflater that should be used to inflate views
     * @param container The parent view that this Controller's view will eventually be attached to.
     *                  This Controller's view should NOT be added in this method. It is simply passed in
     *                  so that valid LayoutParams can be used during inflation.
     */
    @NonNull
    protected abstract View inflateView(@NonNull LayoutInflater inflater, @NonNull ViewGroup container);

    /**
     * Returns the {@link Router} object that can be used for pushing or popping other Controllers
     */
    public final Router getRouter() {
        return mRouter;
    }

    /**
     * Returns any arguments that were set in this Controller's constructor
     */
    public Bundle getArgs() {
        return mArgs;
    }

    /**
     * Adds a child Controller that will be hosted within this Controller. Can be used for nesting
     * Controllers or for presenting Dialog-like Views on top of this Controller.
     */
    public void addChildController(ChildControllerTransaction transaction) {
        addChildController(transaction, transaction.getPushControllerChangeHandler());
    }

    /**
     * Removes a child Controller
     */
    public void removeChildController(Controller controller) {
        for (int i = mChildControllers.size() - 1; i >= 0; i--) {
            ChildControllerTransaction childTransaction = mChildControllers.get(i);
            if (childTransaction.controller == controller) {
                childTransaction.controller.mParentController = null;

                if (controller.mView != null && controller.mView.getParent() != null) {
                    ViewGroup container = (ViewGroup)controller.mView.getParent();
                    ControllerChangeHandler.executeChange(null, controller, false, container, childTransaction.getPopControllerChangeHandler());
                }

                childTransaction.controller.destroy();
                mChildControllers.remove(i);
                break;
            }
        }
    }

    /**
     * Returns whether or not this Controller has been destroyed.
     */
    public final boolean isDestroyed() {
        return mDestroyed;
    }

    /**
     * Returns whether or not this Controller is currently attached to a host View.
     */
    public final boolean isAttached() {
        return mAttached;
    }

    /**
     * Return this Controller's View, if available.
     */
    public final View getView() {
        return mView;
    }

    /**
     * Returns the host Activity of this Controller's {@link Router}
     */
    public final Activity getActivity() {
        return mRouter.getActivity();
    }

    /**
     * Returns the Resources from the host Activity
     */
    public final Resources getResources() {
        return getActivity().getResources();
    }

    /**
     * Returns the Application Context derived from the host Activity
     */
    public final Context getApplicationContext() {
        return getActivity().getApplicationContext();
    }

    /**
     * Returns this Controller's parent Controller if it is a child Controller.
     */
    public final Controller getParentController() {
        return mParentController;
    }

    /**
     * Returns this Controller's instance ID, which is generated when the instance is created and
     * retained across restarts.
     */
    public final String getInstanceId() {
        return mInstanceId;
    }

    /**
     * Returns the child Controller that was added with a given tag, if available.
     *
     * @param tag The tag that was initially passed in with the {@link ChildControllerTransaction}
     * @return The matching child Controller, if one exists
     */
    public final Controller getChildController(String tag) {
        for (ControllerTransaction transaction : mChildControllers) {
            if (tag.equals(transaction.tag)) {
                return transaction.controller;
            }
        }
        return null;
    }

    /**
     * Returns the child Controller with the given instance id, if available.
     *
     * @param instanceId The instance ID being searched for
     * @return The matching child Controller, if one exists
     */
    public final Controller getChildControllerWithInstanceId(String instanceId) {
        for (ControllerTransaction transaction : mChildControllers) {
            if (transaction.controller.getInstanceId().equals(instanceId)) {
                return transaction.controller;
            }
        }
        return null;
    }

    /**
     * Returns all of this Controller's child Controllers
     */
    public final List<Controller> getChildControllers() {
        List<Controller> controllers = new ArrayList<>();
        for (ControllerTransaction transaction : mChildControllers) {
            controllers.add(transaction.controller);
        }
        return controllers;
    }

    /**
     * Optional target for this Controller. One reason this could be used is to send results back to the Controller
     * that started this one. Target Controllers are retained across instances.
     *
     * @param target The Controller that is the target of this one.
     */
    public final void setTargetController(Controller target) {
        mTargetInstanceId = target != null ? target.getInstanceId() : null;
        onTargetControllerSet(target);
    }

    /**
     * This method will be called when {@link #setTargetController(Controller)} is called. It is recommended
     * that Controllers enforce that their target Controller conform to a specific Interface.
     *
     * @param target The Controller that is the target of this one.
     */
    public void onTargetControllerSet(Controller target) { }

    /**
     * Returns the target Controller that was set with the {@link #setTargetController(Controller)} method
     *
     * @return This Controller's target
     */
    public final Controller getTargetController() {
        return mTargetInstanceId != null ? mRouter.getControllerWithInstanceId(mTargetInstanceId) : null;
    }

    /**
     * Called when this Controller's View is inflated. This should overridden to bind the View
     * to variables, either using findViewById or something like Butterknife.
     *
     * @param view The View to which this Controller should be bound.
     */
    protected void onBindView(@NonNull final View view) { }

    /**
     * Called when this Controller's View is being destroyed. This should overridden to unbind the View
     * from any local variables.
     *
     * @param view The View to which this Controller should be bound.
     */
    protected void onUnbindView(View view) { }

    /**
     * Called when this Controller begins the process of being swapped in or out of the host view.
     *
     * @param changeHandler The {@link ControllerChangeHandler} that's managing the swap
     * @param changeType The type of change that's occurring
     */
    protected void onChangeStarted(@NonNull ControllerChangeHandler changeHandler, @NonNull ControllerChangeType changeType) { }

    /**
     * Called when this Controller completes the process of being swapped in or out of the host view.
     *
     * @param changeHandler The {@link ControllerChangeHandler} that's managing the swap
     * @param changeType The type of change that occurred
     */
    protected void onChangeEnded(@NonNull ControllerChangeHandler changeHandler, @NonNull ControllerChangeType changeType) { }

    /**
     * Called when this Controller is attached to its host ViewGroup
     *
     * @param view The View for this Controller (passed for convenience)
     */
    protected void onAttach(@NonNull View view) { }

    /**
     * Called when this Controller is detached from its host ViewGroup
     *
     * @param view The View for this Controller (passed for convenience)
     */
    protected void onDetach(@NonNull View view) { }

    /**
     * Called when this Controller has been destroyed.
     */
    protected void onDestroy() { }

    /**
     * Called when this Controller's host Activity is started
     */
    protected void onActivityStarted(Activity activity) { }

    /**
     * Called when this Controller's host Activity is resumed
     */
    protected void onActivityResumed(Activity activity) { }

    /**
     * Called when this Controller's host Activity is paused
     */
    protected void onActivityPaused(Activity activity) { }

    /**
     * Called when this Controller's host Activity is stopped
     */
    protected void onActivityStopped(Activity activity) { }

    /**
     * Called to save this Controller's View state. As Views can be detached and destroyed as part of the
     * Controller lifecycle (ex: when another Controller has been pushed on top of it), care should be taken
     * to save anything needed to reconstruct the View.
     *
     * @param view This Controller's View, passed for convenience
     * @param outState The Bundle into which the View state should be saved
     */
    protected void onSaveViewState(@NonNull View view, @NonNull Bundle outState) { }

    /**
     * Restores data that was saved in the {@link #onSaveViewState(View, Bundle)} method. This should be overridden
     * to restore the View's state to where it was before it was destroyed.
     *
     * @param view This Controller's View, passed for convenience
     * @param savedViewState The bundle that has data to be restored
     */
    protected void onRestoreViewState(@NonNull View view, @NonNull Bundle savedViewState) { }

    /**
     * Called to save this Controller's state in the event that its host Activity is destroyed.
     *
     * @param outState The Bundle into which data should be saved
     */
    protected void onSaveInstanceState(@NonNull Bundle outState) { }

    /**
     * Restores data that was saved in the {@link #onSaveInstanceState(Bundle)} method. This should be overridden
     * to restore this Controller's state to where it was before it was destroyed.
     *
     * @param savedInstanceState The bundle that has data to be restored
     */
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) { }

    /**
     * Calls startActivityForResult(Intent, int) from this Controller's host Activity.
     */
    public final void startActivityForResult(Intent intent, int requestCode) {
        getRouter().getLifecycleHandler().startActivityForResult(mInstanceId, intent, requestCode);
    }

    /**
     * Calls startActivityForResult(Intent, int, Bundle) from this Controller's host Activity.
     */
    public final void startActivityForResult(Intent intent, int requestCode, Bundle options) {
        getRouter().getLifecycleHandler().startActivityForResult(mInstanceId, intent, requestCode, options);
    }

    /**
     * Should be overridden if this Controller has called startActivityForResult and needs to handle
     * the result.
     *
     * @param requestCode The requestCode passed to startActivityForResult
     * @param resultCode The resultCode that was returned to the host Activity's onActivityResult method
     * @param data The data Intent that was returned to the host Activity's onActivityResult method
     */
    public void onActivityResult(int requestCode, int resultCode, Intent data) { }

    /**
     * Calls requestPermission(String[], int) from this Controller's host Activity. Results for this request,
     * including {@link #shouldShowRequestPermissionRationale(String)} and
     * {@link #onRequestPermissionsResult(int, String[], int[])} will be forwarded back to this Controller by the system.
     */
    @TargetApi(Build.VERSION_CODES.M)
    public final void requestPermissions(@NonNull String[] permissions, int requestCode) {
        mRequestedPermissions.addAll(Arrays.asList(permissions));
        getRouter().getLifecycleHandler().requestPermissions(mInstanceId, permissions, requestCode);
    }

    /**
     * Gets whether you should show UI with rationale for requesting a permission.
     * {@see android.app.Activity#shouldShowRequestPermissionRationale(String)}
     *
     * @param permission A permission this Controller has requested
     */
    public boolean shouldShowRequestPermissionRationale(@NonNull String permission) {
        return false;
    }

    /**
     * Should be overridden if this Controller has requested runtime permissions and needs to handle the user's response.
     *
     * @param requestCode The requestCode that was used to request the permissions
     * @param permissions The array of permissions requested
     * @param grantResults The results for each permission requested
     */
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) { }

    /**
     * Should be overridden if this Controller needs to handle the back button being pressed.
     *
     * @return True if this Controller has consumed the back button press, otherwise false
     */
    public boolean handleBack() {
        for (int i = mChildControllers.size() - 1; i >= 0; i--) {
            ChildControllerTransaction transaction = mChildControllers.get(i);
            if (transaction.addToLocalBackstack) {
                removeChildController(transaction.controller);
                return true;
            }
        }

        return false;
    }

    /**
     * Adds a listener for all of this Controller's lifecycle events
     *
     * @param lifecycleListener The listener
     */
    public void addLifecycleListener(LifecycleListener lifecycleListener) {
        if (!mLifecycleListeners.contains(lifecycleListener)) {
            mLifecycleListeners.add(lifecycleListener);
        }
    }

    /**
     * Removes a previously added lifecycle listener
     *
     * @param lifecycleListener The listener to be removed
     */
    public void removeLifecycleListener(LifecycleListener lifecycleListener) {
        mLifecycleListeners.remove(lifecycleListener);
    }

    /**
     * Returns this Controller's {@link RetainViewMode}. Defaults to {@link RetainViewMode#RELEASE_DETACH}.
     */
    public RetainViewMode getRetainViewMode() {
        return mRetainViewMode;
    }

    /**
     * Sets this Controller's {@link RetainViewMode}, which will influence when its view will be released.
     * This is useful when a Controller's view hierarchy is expensive to tear down and rebuild.
     */
    public void setRetainViewMode(RetainViewMode retainViewMode) {
        mRetainViewMode = retainViewMode;
        if (mRetainViewMode == RetainViewMode.RELEASE_DETACH && !mAttached) {
            removeViewReference();
        }
    }

    /**
     * Returns the {@link ControllerChangeHandler} that should be used for pushing this Controller, or null
     * if the handler from the {@link ControllerTransaction} should be used instead.
     */
    public final ControllerChangeHandler getOverriddenPushHandler() {
        return mOverriddenPushHandler;
    }

    /**
     * Overrides the {@link ControllerChangeHandler} that should be used for pushing this Controller. If this is a
     * non-null value, it will be used instead of the handler from  the {@link ControllerTransaction}.
     */
    public void overridePushHandler(ControllerChangeHandler overriddenPushHandler) {
        mOverriddenPushHandler = overriddenPushHandler;
    }

    /**
     * Returns the {@link ControllerChangeHandler} that should be used for popping this Controller, or null
     * if the handler from the {@link ControllerTransaction} should be used instead.
     */
    public ControllerChangeHandler getOverriddenPopHandler() {
        return mOverriddenPopHandler;
    }

    /**
     * Overrides the {@link ControllerChangeHandler} that should be used for popping this Controller. If this is a
     * non-null value, it will be used instead of the handler from  the {@link ControllerTransaction}.
     */
    public void overridePopHandler(ControllerChangeHandler overriddenPopHandler) {
        mOverriddenPopHandler = overriddenPopHandler;
    }

    final boolean getNeedsAttach() {
        return mNeedsAttach;
    }

    final boolean didRequestPermission(@NonNull String permission) {
        return mRequestedPermissions.contains(permission);
    }

    final void requestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        mRequestedPermissions.removeAll(Arrays.asList(permissions));
        onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    final void setRouter(@NonNull Router router) {
        mRouter = router;

        for (ChildControllerTransaction child : mChildControllers) {
            child.controller.setRouter(router);
        }
    }

    private void addChildController(ChildControllerTransaction transaction, ControllerChangeHandler pushChangeHandler) {
        if (transaction.controller.mParentController == null) {
            transaction.controller.setRouter(mRouter);
            transaction.controller.mParentController = this;
            mChildControllers.add(transaction);
        }

        attachChildController(transaction, pushChangeHandler);
    }

    private void attachChildController(ChildControllerTransaction transaction, ControllerChangeHandler pushChangeHandler) {
        if (mAttached) {
            ViewGroup container = (ViewGroup)mView.findViewById(transaction.containerId);

            if (container != null) {
                View childView = transaction.controller.mView;
                if (childView == null || childView.getParent() != container) {
                    Controller to = transaction.controller;
                    ControllerChangeHandler.executeChange(to, null, true, container, pushChangeHandler);
                }
            }
        }
    }

    final void activityStarted(Activity activity) {
        onActivityStarted(activity);

        for (ChildControllerTransaction child : mChildControllers) {
            child.controller.activityStarted(activity);
        }
    }

    final void activityResumed(Activity activity) {
        onActivityResumed(activity);

        for (ChildControllerTransaction child : mChildControllers) {
            child.controller.activityResumed(activity);
        }
    }

    final void activityPaused(Activity activity) {
        onActivityPaused(activity);

        for (ChildControllerTransaction child : mChildControllers) {
            child.controller.activityPaused(activity);
        }
    }

    final void activityStopped(Activity activity) {
        onActivityStopped(activity);

        for (ChildControllerTransaction child : mChildControllers) {
            child.controller.activityStopped(activity);
        }
    }

    final void activityDestroyed(boolean isChangingConfigurations) {
        if (isChangingConfigurations) {
            removeViewReference();
        } else {
            destroy();
        }

        for (ChildControllerTransaction child : mChildControllers) {
            child.controller.activityDestroyed(isChangingConfigurations);
        }
    }

    private void attach(@NonNull View view) {
        for (LifecycleListener lifecycleListener : mLifecycleListeners) {
            lifecycleListener.preAttach(this, view);
        }

        mAttached = true;
        mNeedsAttach = false;
        mView = view;

        for (ChildControllerTransaction child : mChildControllers) {
            attachChildController(child, new SimpleSwapChangeHandler());
        }

        onAttach(view);

        for (LifecycleListener lifecycleListener : mLifecycleListeners) {
            lifecycleListener.postAttach(this, view);
        }
    }

    private void detach(@NonNull View view) {
        if (mAttached) {
            for (LifecycleListener lifecycleListener : mLifecycleListeners) {
                lifecycleListener.preDetach(this, view);
            }

            saveViewState(view);

            mAttached = false;
            onDetach(view);

            for (ChildControllerTransaction child : mChildControllers) {
                ViewGroup container = (ViewGroup)mView.findViewById(child.containerId);
                if (container != null) {
                    container.removeView(child.controller.getView());
                }
            }

            if (mRetainViewMode == RetainViewMode.RELEASE_DETACH || mDestroyed) {
                removeViewReference();
            }

            for (LifecycleListener lifecycleListener : mLifecycleListeners) {
                lifecycleListener.postDetach(this, view);
            }
        }
    }

    private void removeViewReference() {
        if (mView != null) {
            for (LifecycleListener lifecycleListener : mLifecycleListeners) {
                lifecycleListener.preUnbindView(this, mView);
            }

            onUnbindView(mView);

            mView = null;

            for (LifecycleListener lifecycleListener : mLifecycleListeners) {
                lifecycleListener.postUnbindView(this);
            }
        }
    }

    final View inflate(@NonNull ViewGroup parent) {
        if (mView == null) {
            View view = inflateView(LayoutInflater.from(parent.getContext()), parent);
            bindView(view);
            restoreViewState(view);
            return view;
        } else {
            return mView;
        }
    }

    final void destroy() {
        for (LifecycleListener lifecycleListener : mLifecycleListeners) {
            lifecycleListener.preDestroy(this);
        }

        mDestroyed = true;
        onDestroy();

        for (ChildControllerTransaction child : mChildControllers) {
            child.controller.destroy();
        }

        for (LifecycleListener lifecycleListener : mLifecycleListeners) {
            lifecycleListener.postDestroy(this);
        }
    }

    final void saveViewState(@NonNull View view) {
        mViewState = new Bundle();

        SparseArray<Parcelable> hierarchyState = new SparseArray<>();
        view.saveHierarchyState(hierarchyState);
        mViewState.putSparseParcelableArray(KEY_VIEW_STATE_HIERARCHY, hierarchyState);

        Bundle stateBundle = new Bundle();
        onSaveViewState(view, stateBundle);
        mViewState.putBundle(KEY_VIEW_STATE_BUNDLE, stateBundle);

        for (ChildControllerTransaction child : mChildControllers) {
            if (child.controller.mView != null) {
                child.controller.saveViewState(child.controller.mView);
            }
        }
    }

    final void restoreViewState(@NonNull View view) {
        if (mViewState != null) {
            view.restoreHierarchyState(mViewState.getSparseParcelableArray(KEY_VIEW_STATE_HIERARCHY));
            onRestoreViewState(view, mViewState.getBundle(KEY_VIEW_STATE_BUNDLE));

            for (ChildControllerTransaction child : mChildControllers) {
                if (child.controller.mView != null) {
                    child.controller.restoreViewState(child.controller.mView);
                }
            }
        }
    }

    final Bundle saveInstanceState() {
        if (mAttached && mView != null) {
            saveViewState(mView);
        }

        Bundle outState = new Bundle();
        outState.putString(KEY_CLASS_NAME, getClass().getCanonicalName());
        outState.putBundle(KEY_VIEW_STATE, mViewState);
        outState.putBundle(KEY_ARGS, mArgs);
        outState.putString(KEY_INSTANCE_ID, mInstanceId);
        outState.putString(KEY_TARGET_INSTANCE_ID, mTargetInstanceId);
        outState.putStringArrayList(KEY_REQUESTED_PERMISSIONS, mRequestedPermissions);
        outState.putBoolean(KEY_NEEDS_ATTACH, mNeedsAttach || mAttached);

        if (mOverriddenPushHandler != null) {
            outState.putBundle(KEY_OVERRIDDEN_PUSH_HANDLER, mOverriddenPushHandler.toBundle());
        }
        if (mOverriddenPopHandler != null) {
            outState.putBundle(KEY_OVERRIDDEN_POP_HANDLER, mOverriddenPopHandler.toBundle());
        }

        ArrayList<Bundle> childBundles = new ArrayList<>();
        for (ChildControllerTransaction childController : mChildControllers) {
            childBundles.add(childController.toBundle());
        }
        outState.putParcelableArrayList(KEY_CHILDREN, childBundles);

        Bundle savedState = new Bundle();
        onSaveInstanceState(savedState);

        for (LifecycleListener lifecycleListener : mLifecycleListeners) {
            lifecycleListener.onSaveInstanceState(this, savedState);
        }

        outState.putBundle(KEY_SAVED_STATE, savedState);

        return outState;
    }

    private void restoreInstanceState(@NonNull Bundle savedInstanceState) {
        mViewState = savedInstanceState.getBundle(KEY_VIEW_STATE);
        mInstanceId = savedInstanceState.getString(KEY_INSTANCE_ID);
        mTargetInstanceId = savedInstanceState.getString(KEY_TARGET_INSTANCE_ID);
        mRequestedPermissions.addAll(savedInstanceState.getStringArrayList(KEY_REQUESTED_PERMISSIONS));
        mOverriddenPushHandler = ControllerChangeHandler.fromBundle(savedInstanceState.getBundle(KEY_OVERRIDDEN_PUSH_HANDLER));
        mOverriddenPopHandler = ControllerChangeHandler.fromBundle(savedInstanceState.getBundle(KEY_OVERRIDDEN_POP_HANDLER));
        mNeedsAttach = savedInstanceState.getBoolean(KEY_NEEDS_ATTACH);

        List<Bundle> childBundles = savedInstanceState.getParcelableArrayList(KEY_CHILDREN);
        for (Bundle childBundle : childBundles) {
            addChildController(new ChildControllerTransaction(childBundle));
        }

        Bundle savedState = savedInstanceState.getBundle(KEY_SAVED_STATE);
        onRestoreInstanceState(savedState);

        for (LifecycleListener lifecycleListener : mLifecycleListeners) {
            lifecycleListener.onRestoreInstanceState(this, savedState);
        }
    }

    final void changeStarted(ControllerChangeHandler changeHandler, ControllerChangeType changeType) {
        onChangeStarted(changeHandler, changeType);

        for (LifecycleListener lifecycleListener : mLifecycleListeners) {
            lifecycleListener.onChangeStart(this, changeHandler, changeType);
        }
    }

    final void changeEnded(ControllerChangeHandler changeHandler, ControllerChangeType changeType) {
        onChangeEnded(changeHandler, changeType);

        for (LifecycleListener lifecycleListener : mLifecycleListeners) {
            lifecycleListener.onChangeEnd(this, changeHandler, changeType);
        }
    }

    private void bindView(@NonNull final View view) {
        for (LifecycleListener lifecycleListener : mLifecycleListeners) {
            lifecycleListener.preBindView(this, view);
        }

        onBindView(view);

        view.addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                attach(view);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                detach(view);
            }
        });

        for (LifecycleListener lifecycleListener : mLifecycleListeners) {
            lifecycleListener.postBindView(this, view);
        }
    }

    private void ensureRequiredConstructor() {
        Constructor[] constructors = getClass().getConstructors();
        if (getBundleConstructor(constructors) == null && getDefaultConstructor(constructors) == null) {
            throw new RuntimeException(getClass() + " does not have a constructor that takes a Bundle argument or a default constructor. Controllers must have one of these in order to restore their states.");
        }
    }

    private static Constructor getDefaultConstructor(Constructor[] constructors) {
        for (Constructor constructor : constructors) {
            if (constructor.getParameterTypes().length == 0) {
                return constructor;
            }
        }
        return null;
    }

    private static Constructor getBundleConstructor(Constructor[] constructors) {
        for (Constructor constructor : constructors) {
            if (constructor.getParameterTypes().length == 1 && constructor.getParameterTypes()[0] == Bundle.class) {
                return constructor;
            }
        }
        return null;
    }

    /** Modes that will influence when the Controller will allow its view to be destroyed */
    public enum RetainViewMode {
        /** The Controller will release its reference to its view as soon as it is detached. */
        RELEASE_DETACH,
        /** The Controller will retain its reference to its view when detached, but will still release the reference when a config change occurs. */
        RETAIN_DETACH
    }

    /** Allows external classes to listen for lifecycle events in a Controller */
    public static abstract class LifecycleListener {

        public void onChangeStart(@NonNull Controller controller, @NonNull ControllerChangeHandler changeHandler, @NonNull ControllerChangeType changeType) { }
        public void onChangeEnd(@NonNull Controller controller, @NonNull ControllerChangeHandler changeHandler, @NonNull ControllerChangeType changeType) { }

        public void preBindView(@NonNull Controller controller, @NonNull View view) { }
        public void postBindView(@NonNull Controller controller, @NonNull View view) { }

        public void preAttach(@NonNull Controller controller, @NonNull View view) { }
        public void postAttach(@NonNull Controller controller, @NonNull View view) { }

        public void preUnbindView(@NonNull Controller controller, @NonNull View view) { }
        public void postUnbindView(@NonNull Controller controller) { }

        public void preDetach(@NonNull Controller controller, @NonNull View view) { }
        public void postDetach(@NonNull Controller controller, @NonNull View view) { }

        public void preDestroy(@NonNull Controller controller) { }
        public void postDestroy(@NonNull Controller controller) { }

        public void onSaveInstanceState(@NonNull Controller controller, @NonNull Bundle outState) { }
        public void onRestoreInstanceState(@NonNull Controller controller, @NonNull Bundle savedInstanceState) { }
    }

}