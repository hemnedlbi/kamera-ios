# One-off: adds the KameraWidget (Live Activity) app-extension target to the iOS project.
# Run on macOS CI (needs the xcodeproj gem). Safe to run twice: it stops if the target exists.
require 'xcodeproj'

path = File.expand_path('../../ios/App/App.xcodeproj', __dir__)
project = Xcodeproj::Project.open(path)
if project.targets.any? { |t| t.name == 'KameraWidget' }
  puts 'KameraWidget target already exists'
  exit 0
end

app = project.targets.find { |t| t.name == 'App' } or abort('App target not found')
main = project.main_group
app_group = main.children.find { |g| g.respond_to?(:path) && g.path == 'App' } or abort('App group not found')

# app: Live Activity helper
live_ref = app_group.new_reference('KameraLive.swift')
app.add_file_references([live_ref])

# shared attributes: in both targets
shared = main.new_group('Shared', 'Shared')
attrs_ref = shared.new_reference('KameraActivityAttributes.swift')
app.add_file_references([attrs_ref])

# the extension
wgroup = main.new_group('KameraWidget', 'KameraWidget')
widget_ref = wgroup.new_reference('KameraWidget.swift')
wgroup.new_reference('Info.plist')

ext = project.new_target(:app_extension, 'KameraWidget', :ios, '16.2', nil, :swift)
ext.add_file_references([widget_ref, attrs_ref])
ext.add_system_frameworks(%w[WidgetKit SwiftUI])
ext.build_configurations.each do |c|
  s = c.build_settings
  s['PRODUCT_BUNDLE_IDENTIFIER'] = 'com.chra4.camera.KameraWidget'
  s['PRODUCT_NAME'] = '$(TARGET_NAME)'
  s['INFOPLIST_FILE'] = 'KameraWidget/Info.plist'
  s['GENERATE_INFOPLIST_FILE'] = 'NO'
  s['SWIFT_VERSION'] = '5.0'
  s['TARGETED_DEVICE_FAMILY'] = '1'
  s['IPHONEOS_DEPLOYMENT_TARGET'] = '16.2'
  s['SKIP_INSTALL'] = 'YES'
  s['CODE_SIGN_STYLE'] = 'Automatic'
  s['DEVELOPMENT_TEAM'] = 'GG84JU45K5'
  s['MARKETING_VERSION'] = '1.0'
  s['CURRENT_PROJECT_VERSION'] = '1'
  s['LD_RUNPATH_SEARCH_PATHS'] = ['$(inherited)', '@executable_path/Frameworks', '@executable_path/../../Frameworks']
  s['APPLICATION_EXTENSION_API_ONLY'] = 'YES'
  s['SWIFT_EMIT_LOC_STRINGS'] = 'YES'
  s['ENABLE_USER_SCRIPT_SANDBOXING'] = 'NO'
end

# embed the extension in the app
app.add_dependency(ext)
embed = app.new_copy_files_build_phase('Embed Foundation Extensions')
embed.symbol_dst_subfolder_spec = :plug_ins
bf = embed.add_file_reference(ext.product_reference, true)
bf.settings = { 'ATTRIBUTES' => ['RemoveHeadersOnCopy'] }

project.save
puts 'KameraWidget target added'
