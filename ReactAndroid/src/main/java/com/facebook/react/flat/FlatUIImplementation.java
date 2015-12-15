/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.react.flat;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.uimanager.CatalystStylesDiffMap;
import com.facebook.react.uimanager.ReactShadowNode;
import com.facebook.react.uimanager.UIImplementation;
import com.facebook.react.uimanager.ViewManager;
import com.facebook.react.uimanager.ViewManagerRegistry;
import com.facebook.react.uimanager.events.EventDispatcher;
import com.facebook.react.views.image.ReactImageManager;

/**
 * FlatUIImplementation builds on top of UIImplementation and allows pre-creating everything
 * required for drawing (DrawCommands) and touching (NodeRegions) views in background thread
 * for faster drawing and interactions.
 */
public class FlatUIImplementation extends UIImplementation {

  private final StateBuilder mStateBuilder;

  public static FlatUIImplementation createInstance(
      ReactApplicationContext reactContext,
      List<ViewManager> viewManagers) {

    ReactImageManager reactImageManager = findReactImageManager(viewManagers);
    if (reactImageManager != null) {
      Object callerContext = reactImageManager.getCallerContext();
      if (callerContext != null) {
        RCTImageView.setCallerContext(callerContext);
      }
    }

    TypefaceCache.setAssetManager(reactContext.getAssets());

    viewManagers = new ArrayList<ViewManager>(viewManagers);
    viewManagers.add(new RCTViewManager());
    viewManagers.add(new RCTTextManager());
    viewManagers.add(new RCTRawTextManager());
    viewManagers.add(new RCTVirtualTextManager());
    viewManagers.add(new RCTImageViewManager());

    ViewManagerRegistry viewManagerRegistry = new ViewManagerRegistry(viewManagers);
    FlatNativeViewHierarchyManager nativeViewHierarchyManager = new FlatNativeViewHierarchyManager(
        viewManagerRegistry);
    FlatUIViewOperationQueue operationsQueue = new FlatUIViewOperationQueue(
        reactContext,
        nativeViewHierarchyManager);
    return new FlatUIImplementation(viewManagerRegistry, operationsQueue);
  }

  private FlatUIImplementation(
      ViewManagerRegistry viewManagers,
      FlatUIViewOperationQueue operationsQueue) {
    super(viewManagers, operationsQueue);
    mStateBuilder = new StateBuilder(operationsQueue);
  }

  @Override
  protected ReactShadowNode createRootShadowNode() {
    return new FlatRootShadowNode();
  }

  @Override
  protected void handleCreateView(
      ReactShadowNode cssNode,
      int rootViewTag,
      @Nullable CatalystStylesDiffMap styles) {
    FlatShadowNode node = (FlatShadowNode) cssNode;

    if (styles != null) {
      node.handleUpdateProperties(styles);
    }

    if (node.mountsToView()) {
      int tag = cssNode.getReactTag();
      mStateBuilder.ensureBackingViewIsCreated(node, tag, styles);
    }
  }

  @Override
  protected void handleUpdateView(
      ReactShadowNode cssNode,
      String className,
      CatalystStylesDiffMap styles) {
    FlatShadowNode node = (FlatShadowNode) cssNode;

    node.handleUpdateProperties(styles);

    if (node.mountsToView()) {
      int tag = cssNode.getReactTag();
      mStateBuilder.ensureBackingViewIsCreated(node, tag, styles);
    }
  }

  @Override
  public void manageChildren(
      int viewTag,
      @Nullable ReadableArray moveFrom,
      @Nullable ReadableArray moveTo,
      @Nullable ReadableArray addChildTags,
      @Nullable ReadableArray addAtIndices,
      @Nullable ReadableArray removeFrom) {

    if (moveFrom != null) {
      throw new RuntimeException("Not implemented");
    }
    if (moveTo != null) {
      throw new RuntimeException("Not implemented");
    }

    ReactShadowNode parentNode = resolveShadowNode(viewTag);
    if (removeFrom != null) {
      if (moveFrom != null) {
        // both moveFrom AND removeFrom are present
        throw new RuntimeException("Not implemented, requires merging");
      }

      int numToRemove = removeFrom.size();
      int prevIndex = Integer.MAX_VALUE;
      for (int i = numToRemove - 1; i >= 0; --i) {
        int index = removeFrom.getInt(i);
        if (index >= prevIndex) {
          throw new RuntimeException(
              "Invariant failure, needs sorting! prevIndex: " + prevIndex + " index: " + index);
        }

        ReactShadowNode child = parentNode.removeChildAt(index);
        prevIndex = index;

        removeShadowNode(child);
      }
    }

    if (addChildTags != null) {
      int numNodesToAdd = addChildTags.size();
      for (int i = 0; i < numNodesToAdd; ++i) {
        int childTag = addChildTags.getInt(i);
        ReactShadowNode child = resolveShadowNode(childTag);

        int addAtIndex = addAtIndices.getInt(i);
        parentNode.addChildAt(child, addAtIndex);
      }
    }
  }

  @Override
  protected void calculateRootLayout(ReactShadowNode cssRoot) {
  }

  @Override
  protected void applyUpdatesRecursive(
      ReactShadowNode cssNode,
      float absoluteX,
      float absoluteY,
      EventDispatcher eventDispatcher) {
    FlatRootShadowNode rootNode = (FlatRootShadowNode) cssNode;
    if (!rootNode.needsLayout() && !rootNode.isUpdated()) {
      return;
    }

    super.calculateRootLayout(rootNode);
    rootNode.markUpdated(false);
    mStateBuilder.applyUpdates(rootNode);
  }

  private static @Nullable ReactImageManager findReactImageManager(List<ViewManager> viewManagers) {
    for (int i = 0, size = viewManagers.size(); i != size; ++i) {
      if (viewManagers.get(i) instanceof ReactImageManager) {
        return (ReactImageManager) viewManagers.get(i);
      }
    }

    return null;
  }
}
