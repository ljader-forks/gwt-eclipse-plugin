/*******************************************************************************
 * Copyright 2013 Google Inc. All Rights Reserved.
 *
 * All rights reserved. This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 *******************************************************************************/
package com.google.appengine.eclipse.wtp.server;

import com.google.appengine.eclipse.core.sdk.GaeSdk;
import com.google.appengine.eclipse.wtp.AppEnginePlugin;
import com.google.appengine.eclipse.wtp.runtime.GaeRuntime;
import com.google.appengine.eclipse.wtp.runtime.RuntimeUtils;
import com.google.appengine.eclipse.wtp.utils.IOUtils;
import com.google.common.collect.Lists;
import com.google.gdt.eclipse.core.sdk.SdkUtils;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.eclipse.jdt.launching.IRuntimeClasspathEntry;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.wst.server.core.IModule;
import org.eclipse.wst.server.core.IServer;
import org.eclipse.wst.server.core.ServerPort;
import org.eclipse.wst.server.core.model.IModuleResource;
import org.eclipse.wst.server.core.model.IModuleResourceDelta;
import org.eclipse.wst.server.core.model.ServerBehaviourDelegate;
import org.eclipse.wst.server.core.util.SocketUtil;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.List;
import java.util.Properties;

/**
 * Controls Google App Engine Server state.
 */
public final class GaeServerBehaviour extends ServerBehaviourDelegate {
  private static final String ARG_PORT = "--port=";
  private static final String ARG_DISABLE_UPDATE_CHECK = "--disable_update_check";
  private static final String ARG_ENABLE_AUTO_RELOAD = "-Dappengine.fullscan.seconds=";
  private static final String GAE_DEV_SERVER_MAIN = "com.google.appengine.tools.development.DevAppServerMain";

  private IDebugEventSetListener processListener;
  private PingThread pingThread;

  /**
   * @return the directory at which module will be published.
   */
  public IPath getModuleDeployDirectory(IModule module) {
    return getRuntimeBaseDirectory().append(module.getName());
  }

  /**
   * Returns runtime base directory. Uses temp directory.
   */
  public IPath getRuntimeBaseDirectory() {
    return getTempDirectory(false);
  }

  /**
   * @return start class name
   */
  public String getStartClassName() {
    return GAE_DEV_SERVER_MAIN;
  }

  /**
   * @return moduleID<->publish url mapping loaded from persistence.
   */
  public Properties loadModulePublishLocations() {
    Properties properties = new Properties();
    IPath path = getTempDirectory().append("publish.txt");
    FileInputStream stream = null;
    try {
      stream = new FileInputStream(path.toFile());
      properties.load(stream);
    } catch (Throwable e) {
      // do nothing
    } finally {
      IOUtils.closeQuietly(stream);
    }
    return properties;
  }

  /**
   * Stores moduleID<->publish url mapping.
   */
  public void saveModulePublishLocations(Properties mapping) {
    IPath path = getTempDirectory().append("publish.txt");
    FileOutputStream fos = null;
    try {
      fos = new FileOutputStream(path.toFile());
      mapping.store(fos, "Google App Engine publish data");
    } catch (Throwable e) {
      // do nothing
    } finally {
      IOUtils.closeQuietly(fos);
    }
  }

  /**
   * Convenient accessor to protected member in superclass.
   */
  public void setModulePublishState2(IModule[] module, int state) {
    setModulePublishState(module, state);
  }

  /**
   * Notifies that the server started. Called in separated thread.
   */
  public void setServerStarted() {
    setServerState(IServer.STATE_STARTED);
  }

  @Override
  public void setupLaunchConfiguration(ILaunchConfigurationWorkingCopy workingCopy,
      IProgressMonitor monitor) throws CoreException {
    // program arguments
    workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROGRAM_ARGUMENTS,
        getRuntimeProgramArguments());
    // main type
    workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME,
        getStartClassName());
    // jre
    GaeRuntime runtime = getGaeRuntime();
    IVMInstall vmInstall = runtime.getVMInstall();
    if (vmInstall == null) {
      vmInstall = JavaRuntime.getDefaultVMInstall();
    }
    workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_JRE_CONTAINER_PATH,
        JavaRuntime.newJREContainerPath(vmInstall).toPortableString());
    // vm args
    List<String> configVMArgs = getRuntimeVMArguments();
    workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_VM_ARGUMENTS,
        mergeArgs(configVMArgs));
    // classpath
    IRuntimeClasspathEntry[] currentClasspath = JavaRuntime.computeUnresolvedRuntimeClasspath(workingCopy);
    GaeSdk sdk = RuntimeUtils.getRuntimeSdk(runtime);
    if (sdk == null) {
      throw new CoreException(new Status(IStatus.ERROR, AppEnginePlugin.PLUGIN_ID,
          "AppEngine SDK is missing or invalid."));
    }
    List<IRuntimeClasspathEntry> cpList = Lists.newArrayList(currentClasspath);
    IClasspathEntry[] gaeClasspathEntries = sdk.getClasspathEntries();
    for (IClasspathEntry gaeEntry : gaeClasspathEntries) {
      if (gaeEntry.getEntryKind() == IClasspathEntry.CPE_LIBRARY
          && gaeEntry.getContentKind() == IPackageFragmentRoot.K_BINARY) {
        // add binaries only
        mergeClasspath(cpList, JavaRuntime.newArchiveRuntimeClasspathEntry(gaeEntry.getPath()));
      }
    }
    List<String> list = Lists.newArrayList();
    for (IRuntimeClasspathEntry entry : cpList) {
      try {
        list.add(entry.getMemento());
      } catch (Throwable e) {
        AppEnginePlugin.logMessage("Could not resolve classpath entry: " + entry, e);
      }
    }
    workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_CLASSPATH, list);
    workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_DEFAULT_CLASSPATH, false);
    // working directory
    workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_WORKING_DIRECTORY,
        getGaeServer().getAppDeployDirectory().toOSString());
  }

  @Override
  public void stop(boolean force) {
    // just terminate
    terminate();
  }

  /**
   * Set up process listener to be able to handle debug events.
   */
  protected void addProcessListener(final IProcess newProcess) {
    if (processListener != null || newProcess == null) {
      return;
    }

    processListener = new IDebugEventSetListener() {
      @Override
      public void handleDebugEvents(DebugEvent[] events) {
        if (events != null) {
          int size = events.length;
          for (int i = 0; i < size; i++) {
            if (newProcess != null && newProcess.equals(events[i].getSource())
                && events[i].getKind() == DebugEvent.TERMINATE) {
              stopImpl();
            }
          }
        }
      }
    };
    DebugPlugin.getDefault().addDebugEventListener(processListener);
  }

  /**
   * Convenient method allowing access to protected method in superclass.
   */
  @Override
  protected IModuleResourceDelta[] getPublishedResourceDelta(IModule[] module) {
    return super.getPublishedResourceDelta(module);
  }

  /**
   * Convenient method allowing access to protected method in superclass.
   */
  @Override
  protected IModuleResource[] getResources(IModule[] module) {
    return super.getResources(module);
  }

  /**
   * Build and return program arguments to run server.
   */
  protected String getRuntimeProgramArguments() throws CoreException {
    StringBuilder args = new StringBuilder();
    GaeServer gaeServer = GaeServer.getGaeServer(getServer());
    // port (will be checked for availability later)
    args.append(ARG_PORT);
    ServerPort[] serverPorts = gaeServer.getServerPorts();
    args.append(serverPorts[0].getPort());
    args.append(" ");
    // don't check for updates every run
    // TODO(amitin): make optional?
    args.append(ARG_DISABLE_UPDATE_CHECK);
    args.append(" ");
    // app directory, must be last arg
    args.append(gaeServer.getAppDeployDirectory().toOSString());
    return args.toString();
  }

  @Override
  protected void publishServer(int kind, IProgressMonitor monitor) throws CoreException {
    if (getServer().getRuntime() == null) {
      return;
    }
    setServerPublishState(IServer.PUBLISH_STATE_NONE);
  }

  /**
   * Finalizes preparations to launch server.
   */
  protected void setupLaunch(ILaunch launch, String launchMode, IProgressMonitor monitor)
      throws CoreException {

    GaeServer gaeServer = GaeServer.getGaeServer(getServer());
    if (gaeServer == null) {
      throw new CoreException(new Status(IStatus.ERROR, AppEnginePlugin.PLUGIN_ID,
          "App Engine Server is misconfigured or invalid."));
    }
    // check server port for availability
    for (ServerPort port : gaeServer.getServerPorts()) {
      if (SocketUtil.isPortInUse(port.getPort(), 5)) {
        throw new CoreException(new Status(IStatus.ERROR, AppEnginePlugin.PLUGIN_ID,
            "Cannot start App Engine Server, server port " + port + " is not available."));
      }
    }
    setServerRestartState(false);
    setServerState(IServer.STATE_STARTING);
    setMode(launchMode);
    // ping server to check for startup
    try {
      String url = "http://" + getServer().getHost();
      int port = gaeServer.getServerPorts()[0].getPort();
      if (port != 80) {
        url += ":" + port;
      }
      pingThread = new PingThread(getServer(), url, -1, this);
    } catch (Throwable e) {
      AppEnginePlugin.logMessage("Can't ping for GAE Server startup.", e);
    }
  }

  /**
   * Stops the ping thread and removes debug listener.
   */
  protected void stopImpl() {
    if (pingThread != null) {
      pingThread.stop();
      pingThread = null;
    }
    if (processListener != null) {
      DebugPlugin.getDefault().removeDebugEventListener(processListener);
      processListener = null;
    }
    setServerState(IServer.STATE_STOPPED);
  }

  /**
   * Terminates the server.
   */
  protected void terminate() {
    if (getServer().getServerState() == IServer.STATE_STOPPED) {
      return;
    }
    try {
      setServerState(IServer.STATE_STOPPING);
      ILaunch launch = getServer().getLaunch();
      if (launch != null) {
        launch.terminate();
      }
      stopImpl();
    } catch (Throwable e) {
      AppEnginePlugin.logMessage("Error killing the process", e);
    }
  }

  /**
   * @return {@link GaeRuntime} bound to the server representing by this delegate.
   */
  private GaeRuntime getGaeRuntime() {
    return (GaeRuntime) getServer().getRuntime().loadAdapter(GaeRuntime.class, null);
  }

  /**
   * @return {@link GaeServer} representing by this delegate.
   */
  private GaeServer getGaeServer() {
    return (GaeServer) getServer().loadAdapter(GaeServer.class, null);
  }

  /**
   * @return an array of VM arguments
   */
  private List<String> getRuntimeVMArguments() {
    GaeRuntime gaeRuntime = getGaeRuntime();
    List<String> vmArguments = RuntimeUtils.getDefaultRuntimeVMArguments(gaeRuntime);
    if (gaeRuntime != null
        && SdkUtils.compareVersionStrings(gaeRuntime.getGaeSdkVersion(),
            RuntimeUtils.MIN_SDK_VERSION_USING_AUTORELOAD) >= 0) {
      // add autoreload
      StringBuilder args = new StringBuilder();
      args.append(ARG_ENABLE_AUTO_RELOAD);
      args.append(getGaeServer().getAutoReloadTime());
      vmArguments.add(args.toString());
    }
    return vmArguments;
  }

  /**
   * @return string representation of VM arguments
   */
  private String mergeArgs(List<String> configVMArgs) {
    String args = "";
    if (configVMArgs == null || configVMArgs.isEmpty()) {
      return args;
    }
    for (String arg : configVMArgs) {
      if (arg != null) {
        if (args.length() > 0 && !args.endsWith(" ")) {
          args += " ";
        }
        args += arg;
      }
    }
    return args;
  }

  /**
   * Merge a single classpath entry into the classpath list.
   */
  private void mergeClasspath(List<IRuntimeClasspathEntry> cp, IRuntimeClasspathEntry entry) {
    for (IRuntimeClasspathEntry entry2 : cp) {
      if (entry2.getPath().equals(entry.getPath())) {
        return;
      }
    }
    cp.add(entry);
  }
}