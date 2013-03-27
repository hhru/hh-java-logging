package ru.hh.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggerContextListener;

public class HhLoggingContextFixingListener implements LoggerContextListener {

  @Override
  public boolean isResetResistant() {
    return true;
  }

  @Override
  public void onStart(LoggerContext context) {
    fixContext(context);
  }

  @Override
  public void onReset(LoggerContext context) {
    fixContext(context);
  }

  @Override
  public void onStop(LoggerContext context) {
    fixContext(context); // not needed, but why not ?
  }

  @Override
  public void onLevelChange(Logger logger, Level level) {
    fixContext(logger.getLoggerContext());
  }

  private void fixContext(LoggerContext context) {
    if (context != null && context.isPackagingDataEnabled()) {
      context.setPackagingDataEnabled(false);
    }
  }
}
