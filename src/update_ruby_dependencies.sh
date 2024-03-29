#!/bin/bash

set -e

if [ "x$XROAD_HOME" = "x" ]
then
  XROAD_HOME=`pwd`
fi

source ~/.rvm/scripts/rvm
rvm use jruby-1.7.22

RUBY_PROJECTS="common-ui proxy-ui"

for each in $RUBY_PROJECTS
do
  cd $XROAD_HOME/$each

  echo "Re-installing gems in '$each' - start"

  rm -f Gemfile.lock;gem clean;bundle install

  echo "Re-installing gems in '$each' - finished"
done

# For more accurate tracking when gems were updated.
GEM_UPDATE_LOG_FILE=$HOME/.xroad_gem_updates
echo $(date) | cat >> $GEM_UPDATE_LOG_FILE
