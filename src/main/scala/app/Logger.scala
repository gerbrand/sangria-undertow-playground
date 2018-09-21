package app

import org.slf4j.LoggerFactory

trait Logger {
  def log = LoggerFactory.getLogger(getClass)
}
