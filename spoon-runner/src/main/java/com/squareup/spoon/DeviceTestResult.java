package com.squareup.spoon;

import com.android.ddmlib.logcat.LogCatMessage;
import com.squareup.spoon.misc.StackTrace;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.squareup.spoon.SpoonLogger.logInfo;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Collections.unmodifiableList;

public final class DeviceTestResult {
  /** Separator between screenshot timestamp and tag. */
  public static final String SCREENSHOT_SEPARATOR = Spoon.NAME_SEPARATOR;

  public enum Status {
    PASS, FAIL, ERROR
  }

  private List<Status> status;
  private List<StackTrace> exception;
  private long duration;
  private List<File> screenshots;
  private File animatedGif;
  private List<LogCatMessage> log;
  private List<String> runIds;

  private DeviceTestResult(List<Status> status, List<StackTrace> exception, long duration,
      List<File> screenshots, File animatedGif, List<LogCatMessage> log, List<String> runIds) {
    this.status = status;
    this.exception = exception;
    this.duration = duration;
    this.screenshots = unmodifiableList(new ArrayList<File>(screenshots));
    this.animatedGif = animatedGif;
    this.log = log;
    this.runIds = runIds;
  }

  /** Execution status. */
  public Status getOverallStatus() {
    if(status.contains(Status.FAIL))
    	return Status.FAIL;
    else if (status.contains(Status.ERROR))
    	return Status.ERROR;
    else
    	return status.get(0);
  }
  
  /** Execution status. */
  public List<Status> getStatus() {
	  return status;
  }

  /** Exception thrown during execution. */
  public List<StackTrace> getException() {
    return exception;
  }

  /** Length of test execution, in seconds. */
  public long getDuration() {
    return duration;
  }

  /** Screenshots taken during test. */
  public List<File> getScreenshots() {
    return screenshots;
  }

  /** Animated GIF of screenshots. */
  public File getAnimatedGif() {
    return animatedGif;
  }

  public List<LogCatMessage> getLog() {
    return log;
  }
  
  public List<String> getRunIds() {
	  return runIds;
  }
  
  public void merge(DeviceTestResult other) {
  	this.exception.addAll(other.exception);
  	this.duration += other.duration;
  	LogCatMessage last = this.log.get(this.log.size()-1);
  	String lastId = this.runIds.get(this.runIds.size()-1);
  	try {
  	java.lang.reflect.Field m = LogCatMessage.class.getDeclaredField("mMessage");
  	m.setAccessible(true);
  	m.set(last, last.getMessage() + " --- end of id " + lastId);
  	}
  	catch(Throwable e) {
  		
  	}
  	
  	this.log.addAll(other.log);
  	this.status.addAll(other.status);
  	this.screenshots.addAll(other.screenshots);
  	this.runIds.addAll(other.runIds);
  }

  public static class Builder {
    private final List<File> screenshots = new ArrayList<File>();
    private List<Status> status = new ArrayList<Status>(Arrays.asList(Status.PASS));
    private List<StackTrace> exception = new ArrayList<StackTrace>();
    private long start;
    private long duration = -1;
    private File animatedGif;
    private List<LogCatMessage> log;
    private List<String> runIds = new ArrayList<String>(Arrays.asList("test-id"));
    
    

    public Builder markTestAsFailed(String message) {
      logInfo("->markTestAsFailed" +  "Current status: " + status.get(0) + "Exceptions:" + exception + "Run ids:" + runIds);
      checkNotNull(message);
      checkArgument(status.get(0) == Status.PASS, "Status was already marked as " + status);
      status.set(0, Status.FAIL);
      exception = new ArrayList<StackTrace>();
      exception.add(StackTrace.from(message));
      logInfo("<-markTestAsFailed" +  "Current status: " + status.get(0) + "Exceptions:" + exception + "Run ids:" + runIds);
      return this;
    }

    public Builder markTestAsError(String message) {
      logInfo("->markTestAsError" +  "Current status: " + status.get(0) + "Exceptions:" + exception + "Run ids:" + runIds);
      checkNotNull(message);
      checkArgument(status.get(0) == Status.PASS, "Status was already marked as " + status);
      status.set(0, Status.ERROR);
      exception = new ArrayList<StackTrace>();
      exception.add(StackTrace.from(message));
      logInfo("<-markTestAsError" +  "Current status: " + status.get(0) + "Exceptions:" + exception + "Run ids:" + runIds);
      return this;
    }

    public Builder setLog(List<LogCatMessage> log) {
      checkNotNull(log);
      checkArgument(this.log == null, "Log already added.");
      this.log = log;
      return this;
    }
    
    public Builder setRunIds(List<String> runIds) {
        checkNotNull(runIds);
        this.runIds = runIds;
        return this;
    }

    public Builder startTest() {
      checkArgument(start == 0, "Start already called.");
      start = System.nanoTime();
      return this;
    }

    public Builder endTest() {
      checkArgument(start != 0, "Start was not called.");
      checkArgument(duration == -1, "End was already called.");
      duration = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
      return this;
    }

    public Builder addScreenshot(File screenshot) {
      checkNotNull(screenshot);
      screenshots.add(screenshot);
      return this;
    }

    public Builder setAnimatedGif(File animatedGif) {
      checkNotNull(animatedGif);
      checkArgument(this.animatedGif == null, "Animated GIF already set.");
      this.animatedGif = animatedGif;
      return this;
    }

    public DeviceTestResult build() {
      if (log == null) {
        log = Collections.emptyList();
      }
      
      if(exception.isEmpty())
    	  exception.add(null);
      
      return new DeviceTestResult(status, exception, duration, screenshots, animatedGif, log, runIds);
    }
    
    
  }
}
