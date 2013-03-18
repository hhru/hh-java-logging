/**
 * Logback: the reliable, generic, fast and flexible logging framework.
 * Copyright (C) 1999-2011, QOS.ch. All rights reserved.
 *
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *
 *   or (per the licensee's choosing)
 *
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation.
 */
package ru.hh.logging;

import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.rolling.RolloverFailure;
import ch.qos.logback.core.rolling.helper.CompressionMode;
import ch.qos.logback.core.rolling.helper.Compressor;
import ch.qos.logback.core.rolling.helper.FileNamePattern;
import ch.qos.logback.core.rolling.helper.RenameUtil;
import org.apache.commons.lang.StringUtils;
import java.io.File;
import java.io.IOException;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * <p>Main file is set to {@code $log.dir/appendername.log}. Rolled file is set to {@code $log.dir/appendername.%i}
 * or {@code $log.dir/appendername.%i.gz} depending on compress.
 * Layout pattern is set to {@code $log.pattern}.
 */
public class HhRollingAppender extends FileAppender<ILoggingEvent> {

  public static final int DEFAULT_MIN_INDEX = 1;
  public static final int DEFAULT_MAX_INDEX = 3;
  public static final int DEFAULT_ROLL_HOUR = 0;
  public static final int DEFAULT_ROLL_MINUTE = 0;
  public static final boolean DEFAULT_COMPRESS = false;
  public static final boolean DEFAULT_ROLL_ENABLED = false;
  // the same default as in encoder but it can be set to false for especially intensive logs
  public static final boolean DEFAULT_IMMEDIATE_FLUSH = true;

  /**
   * It's almost always a bad idea to have a large window size, say over 12.
   */
  private static int MAX_WINDOW_SIZE = 12;

  private Integer minIndex;
  private Integer maxIndex;
  private String pattern;
  // these two parameters are useful for testing even if we are not going to use them in prod
  private Integer rollHour = null;
  private Integer rollMinute = null;
  private Boolean compress;
  private Boolean rollEnabled;
  private Boolean immediateFlush;

  private FileNamePattern uncompressedFileNamePattern;
  private FileNamePattern compressedFileNamePattern;
  private RenameUtil util = new RenameUtil();
  private Compressor compressor = null;
  private static final ScheduledThreadPoolExecutor scheduledExecutorService;
  private long nextRolloverMillis;

  static {
    scheduledExecutorService = new ScheduledThreadPoolExecutor(1);
    scheduledExecutorService.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    scheduledExecutorService.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
  }

  public Integer getMinIndex() {
    return minIndex;
  }

  public void setMinIndex(int minIndex) {
    this.minIndex = minIndex;
  }

  public Integer getMaxIndex() {
    return maxIndex;
  }

  public void setMaxIndex(int maxIndex) {
    this.maxIndex = maxIndex;
  }

  public String getPattern() {
    return pattern;
  }

  public void setPattern(String pattern) {
    this.pattern = pattern;
  }

  public Integer getRollHour() {
    return rollHour;
  }

  public void setRollHour(int rollHour) {
    if (rollHour < 0 || rollHour > 23) {
      throw new IllegalArgumentException("The \"RollHour\" value must be in 0...23 range");
    }
    this.rollHour = rollHour;
  }

  public Integer getRollMinute() {
    return rollMinute;
  }

  public void setRollMinute(int rollMinute) {
    if (rollMinute < 0 || rollMinute > 59) {
      throw new IllegalArgumentException("The \"RollMinute\" value must be in 0...59 range");
    }
    this.rollMinute = rollMinute;
  }

  public Boolean getCompress() {
    return compress;
  }

  public void setCompress(boolean compress) {
    this.compress = compress;
  }

  public Boolean getRollEnabled() {
    return rollEnabled;
  }

  public void setRollEnabled(boolean rollEnabled) {
    this.rollEnabled = rollEnabled;
  }

  public Boolean getImmediateFlush() {
    return immediateFlush;
  }

  public void setImmediateFlush(boolean immediateFlush) {
    this.immediateFlush = immediateFlush;
  }

  private int calcParameter(Integer parameter, String propName, int defaultValue) {
    final String propValue = context.getProperty(propName);
    if (parameter != null) {
      return parameter;
    } else if (!StringUtils.isBlank(propValue)) {
      return Integer.valueOf(propValue.trim());
    } else {
      return defaultValue;
    }
  }

  private boolean calcParameter(Boolean parameter, String propName, boolean defaultValue) {
    final String propValue = context.getProperty(propName);
    if (parameter != null) {
      return parameter;
    } else if (!StringUtils.isBlank(propValue)) {
      return Boolean.valueOf(propValue.trim());
    } else {
      return defaultValue;
    }
  }

  @Override
  public void start() {
    // we don't want to void existing log files
    if (!append) {
      addWarn("Append mode is mandatory for HhRollingFileAppender");
      append = true;
    }

    final String propLogdir = context.getProperty("log.dir");
    final String propPattern = context.getProperty("log.pattern");
    if (pattern == null) {
      pattern = propPattern;
    }
    minIndex = calcParameter(minIndex, "log.index.min", DEFAULT_MIN_INDEX);
    maxIndex = calcParameter(maxIndex, "log.index.max", DEFAULT_MAX_INDEX);
    compress = calcParameter(compress, "log.roll.compress", DEFAULT_COMPRESS);
    rollHour = calcParameter(rollHour, "log.roll.hour", DEFAULT_ROLL_HOUR);
    rollMinute = calcParameter(rollMinute, "log.roll.minute", DEFAULT_ROLL_MINUTE);
    rollEnabled = calcParameter(rollEnabled, "log.roll.enabled", DEFAULT_ROLL_ENABLED);
    immediateFlush = calcParameter(immediateFlush, "log.immediate.flush", DEFAULT_IMMEDIATE_FLUSH);

    if (fileName == null) {
      if (StringUtils.isBlank(propLogdir)) {
        String error = "filename is not set for appender \"" + getName() + "\" while logback property log.dir is also not set";
        addError(error);
        throw new IllegalStateException(error);
      }
      if (getName().matches(".*(\\.\\.|/|\\\\).*")) {
        String error = "appender name cannot have filesystem path elements: " + getName();
        addError(error);
        throw new IllegalStateException(error);
      }
      setFile(String.format("%s/%s.log", propLogdir, getName()));
    }

    if (isPrudent()) {
      addError("Prudent mode is not supported with HhRollingAppender.");
      throw new IllegalStateException("Prudent mode is not supported.");
    }

    if (maxIndex < minIndex) {
      addWarn("MaxIndex (" + maxIndex + ") cannot be smaller than MinIndex ("
          + minIndex + ").");
      addWarn("Setting maxIndex to equal minIndex.");
      maxIndex = minIndex;
    }

    if ((maxIndex - minIndex) > MAX_WINDOW_SIZE) {
      addWarn("Large window sizes are not allowed.");
      maxIndex = minIndex + MAX_WINDOW_SIZE;
      addWarn("MaxIndex reduced to " + maxIndex);
    }

    uncompressedFileNamePattern = new FileNamePattern(rawFileProperty() + ".%i", this.context);
    compressedFileNamePattern = new FileNamePattern(rawFileProperty() + ".%i.gz", this.context);

    if (compress) {
      compressor = new Compressor(CompressionMode.GZ);
      compressor.setContext(this.context);
    }

    if (encoder == null) {
      PatternLayoutEncoder encoder = new PatternLayoutEncoder();
      encoder.setContext(context);
      encoder.setPattern(pattern);
      encoder.setImmediateFlush(immediateFlush);
      setEncoder(encoder);
      encoder.start();
    }

    File logFile = new File(rawFileProperty());
    addInfo("Active log file name: " + getFile());

    if (rollEnabled) {
      // roll logs if we missed the last roll time
      nextRolloverMillis = previousRolloverMillis();
      if (logFile.exists() && logFile.lastModified() < nextRolloverMillis) {
        try {
          // the log file is not opened yet, so calling rolloverImpl instead of rollover()
          rolloverImpl();
          scheduleCompressTask();
        } catch (RolloverFailure failure) {
          addError("Ouch. Failed to roll logs for " + getName() + " log , will try again tomorrow ", failure);
        }
      }
      scheduleNextRolloverTask();
    }

    super.start();
  }

  @Override
  public void stop() {
    scheduledExecutorService.shutdown();
    super.stop();
  }

  private void scheduleNextRolloverTask() {
    try {
      nextRolloverMillis = nextRolloverMillis + TimeUnit.DAYS.toMillis(1);
      scheduledExecutorService.schedule(
          new Runnable() {
            @Override
            public void run() {
              rollover();
              if (compress) {
                scheduleCompressTask();
              }
              scheduleNextRolloverTask();
            }
          },
          nextRolloverMillis - System.currentTimeMillis(),
          TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException ignore) {
      // the process must be shutting down, that's ok
    }
  }

  private void scheduleCompressTask() {
    try {
      // schedule with 1 minute delay so that all logs are rolled first
      // and only after that the compression task starts
      scheduledExecutorService.schedule(new Runnable() {
        @Override
        public void run() {
          compressImpl();
        }
      }, 1, TimeUnit.MINUTES);
    } catch (RejectedExecutionException ignore) {
      // the process must be shutting down, that's ok
    }
  }

  private void rollover() {
    synchronized (lock) {
      if (!new File(rawFileProperty()).exists()) {
        // nothing to roll
        return;
      }
      this.closeOutputStream();
      try {
        rolloverImpl();
      } catch (RolloverFailure failure) {
        addWarn("RolloverFailure occurred. Deferring roll-over.");
        // we failed to roll-over, let us not truncate and risk data loss
        this.append = true;
      }
      try {
        // This will also close the file. This is OK since multiple
        // close operations are safe.
        this.openFile(rawFileProperty());
      } catch (IOException e) {
        addError("openFile(" + rawFileProperty() + ") call failed.", e);
      }
    }
  }

  private void rolloverImpl() throws RolloverFailure {
    // If maxIndex <= 0, then there is no file renaming to be done.
    if (maxIndex >= 0) {
      // Delete the oldest file, to keep Windows happy.
      File file = new File(uncompressedFileNamePattern.convertInt(maxIndex));
      if (file.exists()) {
        if (!file.delete()) {
          throw new RolloverFailure("Could not delete old log: " + file.getAbsolutePath());
        }
      }

      file = new File(compressedFileNamePattern.convertInt(maxIndex));
      if (file.exists()) {
        if (!file.delete()) {
          throw new RolloverFailure("Could not delete old log: " + file.getAbsolutePath());
        }
      }

      // Map {(maxIndex - 1), ..., minIndex} to {maxIndex, ..., minIndex+1}
      for (int i = maxIndex - 1; i >= minIndex; i--) {
        String toRenameFilename = compressedFileNamePattern.convertInt(i);
        // no point in trying to rename an non-existent file
        if (new File(toRenameFilename).exists()) {
          util.rename(toRenameFilename, compressedFileNamePattern.convertInt(i + 1));
        }
        toRenameFilename = uncompressedFileNamePattern.convertInt(i);
        if (new File(toRenameFilename).exists()) {
          util.rename(toRenameFilename, uncompressedFileNamePattern.convertInt(i + 1));
        }
      }

      util.rename(rawFileProperty(), uncompressedFileNamePattern
          .convertInt(minIndex));
    }
  }

  private void compressImpl() {
    for (int i = maxIndex - 1; i >= minIndex; i--) {
      String uncompressedFileName = uncompressedFileNamePattern.convertInt(i);
      File uncompressedFile = new File(uncompressedFileName);
      if (!uncompressedFile.exists()) {
        continue;
      }
      String compressedFileName = compressedFileNamePattern.convertInt(i);
      File potentiallyCorruptedCompressedFile = new File(compressedFileName);
      if (potentiallyCorruptedCompressedFile.exists()) {
        // it seems that previous compression attempt failed
        addInfo("Going to delete potentially corrupted archive " + compressedFileName + " (uncompressed version is present)");
        if (!potentiallyCorruptedCompressedFile.delete()) {
          addWarn("Could not delete " + compressedFileName);
          continue;
        }
      }
      addInfo("Going to gzip " + uncompressedFileName + " to " + compressedFileName);
      compressor.compress(uncompressedFileName, compressedFileName, null);
      addInfo("Gzipped " + uncompressedFileName + " to " + compressedFileName);
    }
  }

  private long previousRolloverMillis() {
    Calendar date = new GregorianCalendar();
    date.set(Calendar.HOUR_OF_DAY, rollHour);
    date.set(Calendar.MINUTE, rollMinute);
    date.set(Calendar.SECOND, 0);
    date.set(Calendar.MILLISECOND, 0);
    // note: this does not work properly with old sun jdk i.e. 1.6.0_26
    // due to MSK timezone issues, need to use fresh jdk/jre
    if (System.currentTimeMillis() <= date.getTimeInMillis()) {
      date.add(Calendar.DAY_OF_MONTH, -1);
    }
    return date.getTimeInMillis();
  }

  private long nextRolloverMillis() {
    Calendar date = new GregorianCalendar();
    date.set(Calendar.HOUR_OF_DAY, rollHour);
    date.set(Calendar.MINUTE, rollMinute);
    date.set(Calendar.SECOND, 0);
    date.set(Calendar.MILLISECOND, 0);
    // note: this does not work properly with old sun jdk i.e. 1.6.0_26
    // due to MSK timezone issues, need to use fresh jdk/jre
    if (System.currentTimeMillis() > date.getTimeInMillis()) {
      date.add(Calendar.DAY_OF_MONTH, 1);
    }
    return date.getTimeInMillis();
  }

  /**
   * This method differentiates HhRollingFileAppender from its super class.
   */
  @Override
  protected void subAppend(ILoggingEvent event) {
    // The roll-over must not interfere with actual writing, so both methods are synchronized.
    synchronized (lock) {
      super.subAppend(event);
    }
  }
}
