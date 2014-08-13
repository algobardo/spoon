package com.squareup.spoon;

import com.android.ddmlib.logcat.LogCatMessage;
import com.squareup.spoon.misc.StackTrace;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Collections.unmodifiableList;

public final class DeviceTestResult {
  /** Separator between screenshot timestamp and tag. */
  public static final String SCREENSHOT_SEPARATOR = Spoon.NAME_SEPARATOR;

  public enum Status {
    PASS, FAIL, ERROR
  }

  private Status status;
  private StackTrace exception;
  private long duration;
  private List<File> screenshots;
  private File animatedGif;
  private List<LogCatMessage> log;
  private List runIds;

  private DeviceTestResult(Status status, StackTrace exception, long duration,
      List<File> screenshots, File animatedGif, List<LogCatMessage> log, List<String> runIds) {
    this.status = status;
    this.exception = exception;
    this.duration = duration;
    this.screenshots = unmodifiableList(new ArrayList<File>(screenshots));
    this.animatedGif = animatedGif;
    this.log = new ArrayList<LogCatMessage>(log);
    this.runIds = new ArrayList<String>(runIds);
  }

  /** Execution status. */
  public Status getStatus() {
    return status;
  }

  /** Exception thrown during execution. */
  public StackTrace getException() {
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
  
  public void merge(DeviceTestResult other) {
  	if(this.exception == null) 
  		this.exception = other.exception;
  	this.duration += other.duration;
  	this.log.addAll(other.log);
  	if(this.status == Status.FAIL || other.status == Status.FAIL)
  		this.status = Status.FAIL;
  	else if(this.status == Status.ERROR || other.status == Status.ERROR)
  		this.status = Status.ERROR;
  	this.screenshots.addAll(other.screenshots);
  	this.runIds.addAll(other.runIds);
  }

  public static class Builder {
    private final List<File> screenshots = new ArrayList<File>();
    private Status status = Status.PASS;
    private StackTrace exception;
    private long start;
    private long duration = -1;
    private File animatedGif;
    private List<LogCatMessage> log;
    private List<String> runIds;

    public Builder markTestAsFailed(String message) {
      checkNotNull(message);
      checkArgument(status == Status.PASS, "Status was already marked as " + status);
      status = Status.FAIL;
      exception = StackTrace.from(message);
      return this;
    }

    public Builder markTestAsError(String message) {
      checkNotNull(message);
      checkArgument(status == Status.PASS, "Status was already marked as " + status);
      status = Status.ERROR;
      exception = StackTrace.from(message);
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
        checkArgument(this.runIds == null, "Run IDs already added.");
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
      if(runIds == null) {
    	  runIds = new ArrayList();
    	  runIds.add("testId");
      }
      
      return new DeviceTestResult(status, exception, duration, screenshots, animatedGif, log, runIds);
    }
    
    
  }
}
