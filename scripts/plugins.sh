#!/usr/bin/env bash
set -euo pipefail

# Vagrant's isolated resolver needs an explicit racc for Nokogiri.
gems="${VAGRANT_HOME:-$HOME/.vagrant.d}/gems/$(ruby -e 'print RUBY_VERSION')"
if [[ ! -f $gems/specifications/racc-$LAB_VAGRANT_RACC_VERSION.gemspec ]]; then
  gem install racc --version "$LAB_VAGRANT_RACC_VERSION" --install-dir "$gems" --no-document
fi

vagrant plugin install vagrant-libvirt --plugin-version "$LAB_VAGRANT_LIBVIRT_VERSION" \
  --plugin-clean-sources --plugin-source https://rubygems.org
