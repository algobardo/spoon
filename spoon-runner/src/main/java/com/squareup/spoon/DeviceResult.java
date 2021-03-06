package com.squareup.spoon;

import com.squareup.spoon.misc.StackTrace;
import com.sun.org.apache.xalan.internal.lib.ExsltSets;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import com.google.common.collect.Sets;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;
import static com.squareup.spoon.SpoonLogger.logInfo;

/** Represents the results of executing instrumentation tests on a single device. */
public final class DeviceResult {
  private final boolean installFailed;
  private final String installMessage;
  private final DeviceDetails deviceDetails;
  private final Map<DeviceTest, DeviceTestResult> testResults;
  private final long started;
  private final long duration;
  private final List<StackTrace> exceptions;


  private DeviceResult(boolean installFailed, String installMessage, DeviceDetails deviceDetails,
      Map<DeviceTest, DeviceTestResult> testResults, long started, long duration,
      List<StackTrace> exceptions) {
    this.installFailed = installFailed;
    this.installMessage = installMessage;
    this.deviceDetails = deviceDetails;
    this.started = started;
    this.testResults = unmodifiableMap(new TreeMap<DeviceTest, DeviceTestResult>(testResults));
    this.duration = duration;
    this.exceptions = unmodifiableList(new ArrayList<StackTrace>(exceptions));
  }

  /**
   * {@code true} if either application or instrumentation APK failed to install.
   *
   * @see #getInstallMessage()
   */
  public boolean getInstallFailed() {
    return installFailed;
  }

  /**
   * Installation failure message. Only present if application or instrumentation APK installation
   * failed.
   *
   * @see #getInstallFailed()
   */
  public String getInstallMessage() {
    return installMessage;
  }

  /** Configuration and hardware device details. */
  public DeviceDetails getDeviceDetails() {
    return deviceDetails;
  }

  /** Individual test results. */
  public Map<DeviceTest, DeviceTestResult> getTestResults() {
    return testResults;
  }

  /** Execution start time. */
  public long getStarted() {
    return started;
  }

  /** Length (in seconds) of execution of all tests on device, or {@code -1} if none ran. */
  public long getDuration() {
    return duration;
  }

  /** Exceptions that occurred during execution. */
  public List<StackTrace> getExceptions() {
    return exceptions;
  }

  /* Aggregate two devices results into one, assuming no DeviceTest has been run twice */
  public DeviceResult aggregate (DeviceResult dres){
      if(this.installFailed || dres.installFailed)
          throw new RuntimeException("The two results are incomparable, at least one fails to install");
        //this.installMessage = installMessage &&
        //this.deviceDetails = deviceDetails;
      //this.started = started;
      long newDuration = this.duration + dres.duration;

      Sets.SetView<DeviceTest> commontest = Sets.intersection(testResults.keySet(), dres.testResults.keySet());
      
      Map<DeviceTest, DeviceTestResult> newResults = new TreeMap<DeviceTest, DeviceTestResult>();
     
      
      Iterator<DeviceTest> it = dres.testResults.keySet().iterator();
      while(it.hasNext()) {
    	  DeviceTest dt = it.next();
    	  if(!commontest.contains(dt))
    		  newResults.put(dt, dres.testResults.get(dt));
      }
      
      it = commontest.iterator();
      while(it.hasNext()) {
    	  DeviceTest dt = it.next();
    	  this.testResults.get(dt).merge(dres.testResults.get(dt));
      }
      
      newResults.putAll(this.testResults);
     
      newResults = unmodifiableMap(newResults);

      ArrayList<StackTrace> newExceptions = new ArrayList<StackTrace>();
      if(this.exceptions != null) newExceptions.addAll(this.exceptions);
      if(dres.exceptions != null) newExceptions.addAll(dres.exceptions);

      return new DeviceResult(installFailed,installMessage,
              deviceDetails,newResults,started,newDuration,newExceptions);
  }

  static class Builder {
    private boolean installFailed = false;
    private String installMessage = null;
    private final Map<DeviceTest, DeviceTestResult.Builder> testResultBuilders =
        new HashMap<DeviceTest, DeviceTestResult.Builder>();
    private DeviceDetails deviceDetails = null;
    private final long started = new Date().getTime();
    private long start;
    private long duration = -1;
    private final List<StackTrace> exceptions = new ArrayList<StackTrace>();

    public Builder addTestResultBuilder(DeviceTest test,
        DeviceTestResult.Builder methodResultBuilder) {
      checkArgument(!installFailed, "Cannot add test result builder when install failed.");
      checkNotNull(methodResultBuilder);
      testResultBuilders.put(test, methodResultBuilder);
      return this;
    }

    public DeviceTestResult.Builder getMethodResultBuilder(DeviceTest test) {
      return testResultBuilders.get(test);
    }

    public Builder setDeviceDetails(DeviceDetails deviceDetails) {
      checkNotNull(deviceDetails);
      this.deviceDetails = deviceDetails;
      return this;
    }

    public Builder markInstallAsFailed(String message) {
      checkNotNull(message);
      checkArgument(!installFailed, "Install already marked as failed.");
      installFailed = true;
      installMessage = message;
      return this;
    }

    public Builder startTests() {
      checkArgument(!installFailed, "Cannot start tests when install failed.");
      checkArgument(start == 0, "Start already called.");
      start = System.nanoTime();
      return this;
    }

    public Builder endTests() {
      checkArgument(start != 0, "Start was not called.");
      checkArgument(duration == -1, "End was already called.");
      duration = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
      return this;
    }

    public Builder addException(Throwable throwable) {
      checkNotNull(throwable);
      exceptions.add(StackTrace.from(throwable));
      return this;
    }

    public Builder addException(String message) {
      checkNotNull(message);
      exceptions.add(StackTrace.from(message));
      return this;
    }

    public DeviceResult build() {
      // Convert builders to actual instances.
      Map<DeviceTest, DeviceTestResult> testResults = new HashMap<DeviceTest, DeviceTestResult>();
      for (Map.Entry<DeviceTest, DeviceTestResult.Builder> entry : testResultBuilders.entrySet()) {
        testResults.put(entry.getKey(), entry.getValue().build());
      }

      return new DeviceResult(installFailed, installMessage, deviceDetails, testResults, started,
          duration, exceptions);
    }
  }
}
