"""ios/FrameKit.xcodeproj를 만든다. 폴더 동기화 그룹(Xcode 16+)이라 소스 파일을 더해도 다시 만들 필요가 없다.

빌드 설정·대상을 바꿀 때만 고치고 실행한다: python3 ios/scripts/generate-project.py ios/FrameKit.xcodeproj/project.pbxproj
"""
import hashlib, sys, os

out = sys.argv[1]
def oid(name):
    return hashlib.md5(name.encode()).hexdigest()[:24].upper()

I = {k: oid(k) for k in """project main products fw_group sample_group tests_group frameworks_group
fw_product sample_product tests_product core_xcf
fw_target sample_target tests_target
fw_sources fw_frameworks fw_resources fw_headers sample_sources sample_frameworks sample_resources sample_embed tests_sources tests_frameworks tests_resources
core_in_fw fw_in_sample fw_embed_in_sample fw_in_tests
fw_dep sample_proxy tests_dep tests_proxy
proj_cfgs proj_debug proj_release fw_cfgs fw_debug fw_release sample_cfgs sample_debug sample_release tests_cfgs tests_debug tests_release""".split()}

common_proj = """				ALWAYS_SEARCH_USER_PATHS = NO;
				CLANG_ENABLE_MODULES = YES;
				CLANG_ENABLE_OBJC_ARC = YES;
				CURRENT_PROJECT_VERSION = 1;
				ENABLE_STRICT_OBJC_MSGSEND = YES;
				"EXCLUDED_ARCHS[sdk=iphonesimulator*]" = x86_64;
				ENABLE_USER_SCRIPT_SANDBOXING = YES;
				GCC_C_LANGUAGE_STANDARD = gnu17;
				IPHONEOS_DEPLOYMENT_TARGET = 17.0;
				LOCALIZATION_PREFERS_STRING_CATALOGS = YES;
				MARKETING_VERSION = "1.0.0-alpha09";
				SDKROOT = iphoneos;
				STRING_CATALOG_GENERATE_SYMBOLS = NO;
				SWIFT_EMIT_LOC_STRINGS = YES;
				SWIFT_VERSION = 6.0;
				TARGETED_DEVICE_FAMILY = "1,2";
"""
def cfg(id_, name, body):
    return f"""		{id_} /* {name} */ = {{
			isa = XCBuildConfiguration;
			buildSettings = {{
{body}			}};
			name = {name};
		}};
"""
proj_debug = common_proj + """				DEBUG_INFORMATION_FORMAT = dwarf;
				ENABLE_TESTABILITY = YES;
				GCC_OPTIMIZATION_LEVEL = 0;
				ONLY_ACTIVE_ARCH = YES;
				SWIFT_ACTIVE_COMPILATION_CONDITIONS = "DEBUG $(inherited)";
				SWIFT_OPTIMIZATION_LEVEL = "-Onone";
"""
proj_release = common_proj + """				DEBUG_INFORMATION_FORMAT = "dwarf-with-dsym";
				ENABLE_NS_ASSERTIONS = NO;
				SWIFT_COMPILATION_MODE = wholemodule;
				VALIDATE_PRODUCT = YES;
"""
fw = """				BUILD_LIBRARY_FOR_DISTRIBUTION = YES;
				CODE_SIGN_STYLE = Automatic;
				DYLIB_COMPATIBILITY_VERSION = 1;
				DYLIB_CURRENT_VERSION = 1;
				DYLIB_INSTALL_NAME_BASE = "@rpath";
				GENERATE_INFOPLIST_FILE = YES;
				INFOPLIST_KEY_NSHumanReadableCopyright = "";
				INSTALL_PATH = "$(LOCAL_LIBRARY_DIR)/Frameworks";
				MTLLINKER_FLAGS = "-cikernel";
				MTL_COMPILER_FLAGS = "-fcikernel";
				MTL_FAST_MATH = NO;
				LD_RUNPATH_SEARCH_PATHS = ("$(inherited)", "@executable_path/Frameworks", "@loader_path/Frameworks");
				OTHER_LDFLAGS = ("$(inherited)", "-lc++");
				PRODUCT_BUNDLE_IDENTIFIER = com.naurylab.framekit;
				PRODUCT_NAME = "$(TARGET_NAME:c99extidentifier)";
				SKIP_INSTALL = NO;
				SWIFT_INSTALL_OBJC_HEADER = NO;
"""
sample = """				ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon;
				CODE_SIGN_STYLE = Automatic;
				GENERATE_INFOPLIST_FILE = YES;
				INFOPLIST_KEY_CFBundleDisplayName = FrameKit;
				INFOPLIST_KEY_NSCameraUsageDescription = "사진을 찍어 바로 편집합니다.";
				INFOPLIST_KEY_UIApplicationSceneManifest_Generation = YES;
				INFOPLIST_KEY_UILaunchScreen_Generation = YES;
				INFOPLIST_KEY_UISupportedInterfaceOrientations = "UIInterfaceOrientationPortrait UIInterfaceOrientationLandscapeLeft UIInterfaceOrientationLandscapeRight UIInterfaceOrientationPortraitUpsideDown";
				INFOPLIST_KEY_UISupportedInterfaceOrientations_iPad = "UIInterfaceOrientationPortrait UIInterfaceOrientationPortraitUpsideDown UIInterfaceOrientationLandscapeLeft UIInterfaceOrientationLandscapeRight";
				LD_RUNPATH_SEARCH_PATHS = ("$(inherited)", "@executable_path/Frameworks");
				PRODUCT_BUNDLE_IDENTIFIER = com.naurylab.framekit.sample;
				PRODUCT_NAME = FrameKitSample;
"""
tests = """				CODE_SIGN_STYLE = Automatic;
				GENERATE_INFOPLIST_FILE = YES;
				PRODUCT_BUNDLE_IDENTIFIER = com.naurylab.framekit.tests;
				PRODUCT_NAME = "$(TARGET_NAME)";
"""

def phase(id_, isa, files, extra=""):
    fl = "".join(f"\t\t\t\t{f},\n" for f in files)
    return f"""		{id_} = {{
			isa = {isa};
			buildActionMask = 2147483647;
			files = (
{fl}			);
{extra}			runOnlyForDeploymentPostprocessing = 0;
		}};
"""

def target(id_, name, cfgs, phases, deps, groups, product, ptype):
    ph = "".join(f"\t\t\t\t{p},\n" for p in phases)
    dp = "".join(f"\t\t\t\t{d},\n" for d in deps)
    gp = "".join(f"\t\t\t\t{g},\n" for g in groups)
    return f"""		{id_} /* {name} */ = {{
			isa = PBXNativeTarget;
			buildConfigurationList = {cfgs};
			buildPhases = (
{ph}			);
			buildRules = (
			);
			dependencies = (
{dp}			);
			fileSystemSynchronizedGroups = (
{gp}			);
			name = {name};
			productName = {name};
			productReference = {product};
			productType = "{ptype}";
		}};
"""

s = f"""// !$*UTF8*$!
{{
	archiveVersion = 1;
	classes = {{
	}};
	objectVersion = 77;
	objects = {{

/* Begin PBXBuildFile section */
		{I['core_in_fw']} /* FrameKitCore.xcframework in Frameworks */ = {{isa = PBXBuildFile; fileRef = {I['core_xcf']}; }};
		{I['fw_in_sample']} /* FrameKit.framework in Frameworks */ = {{isa = PBXBuildFile; fileRef = {I['fw_product']}; }};
		{I['fw_embed_in_sample']} /* FrameKit.framework in Embed Frameworks */ = {{isa = PBXBuildFile; fileRef = {I['fw_product']}; settings = {{ATTRIBUTES = (CodeSignOnCopy, RemoveHeadersOnCopy, ); }}; }};
		{I['fw_in_tests']} /* FrameKit.framework in Frameworks */ = {{isa = PBXBuildFile; fileRef = {I['fw_product']}; }};
/* End PBXBuildFile section */

/* Begin PBXContainerItemProxy section */
		{I['sample_proxy']} = {{
			isa = PBXContainerItemProxy;
			containerPortal = {I['project']};
			proxyType = 1;
			remoteGlobalIDString = {I['fw_target']};
			remoteInfo = FrameKit;
		}};
		{I['tests_proxy']} = {{
			isa = PBXContainerItemProxy;
			containerPortal = {I['project']};
			proxyType = 1;
			remoteGlobalIDString = {I['fw_target']};
			remoteInfo = FrameKit;
		}};
/* End PBXContainerItemProxy section */

/* Begin PBXCopyFilesBuildPhase section */
		{I['sample_embed']} /* Embed Frameworks */ = {{
			isa = PBXCopyFilesBuildPhase;
			buildActionMask = 2147483647;
			dstPath = "";
			dstSubfolderSpec = 10;
			files = (
				{I['fw_embed_in_sample']},
			);
			name = "Embed Frameworks";
			runOnlyForDeploymentPostprocessing = 0;
		}};
/* End PBXCopyFilesBuildPhase section */

/* Begin PBXFileReference section */
		{I['fw_product']} /* FrameKit.framework */ = {{isa = PBXFileReference; explicitFileType = wrapper.framework; includeInIndex = 0; path = FrameKit.framework; sourceTree = BUILT_PRODUCTS_DIR; }};
		{I['sample_product']} /* FrameKitSample.app */ = {{isa = PBXFileReference; explicitFileType = wrapper.application; includeInIndex = 0; path = FrameKitSample.app; sourceTree = BUILT_PRODUCTS_DIR; }};
		{I['tests_product']} /* FrameKitTests.xctest */ = {{isa = PBXFileReference; explicitFileType = wrapper.cfbundle; includeInIndex = 0; path = FrameKitTests.xctest; sourceTree = BUILT_PRODUCTS_DIR; }};
		{I['core_xcf']} /* FrameKitCore.xcframework */ = {{isa = PBXFileReference; lastKnownFileType = wrapper.xcframework; path = FrameKitCore.xcframework; sourceTree = "<group>"; }};
/* End PBXFileReference section */

/* Begin PBXFileSystemSynchronizedRootGroup section */
		{I['fw_group']} /* FrameKit */ = {{isa = PBXFileSystemSynchronizedRootGroup; path = FrameKit; sourceTree = "<group>"; }};
		{I['sample_group']} /* Sample */ = {{isa = PBXFileSystemSynchronizedRootGroup; path = Sample; sourceTree = "<group>"; }};
		{I['tests_group']} /* FrameKitTests */ = {{isa = PBXFileSystemSynchronizedRootGroup; path = FrameKitTests; sourceTree = "<group>"; }};
/* End PBXFileSystemSynchronizedRootGroup section */

/* Begin PBXFrameworksBuildPhase section */
{phase(I['fw_frameworks'], 'PBXFrameworksBuildPhase', [I['core_in_fw']])}{phase(I['sample_frameworks'], 'PBXFrameworksBuildPhase', [I['fw_in_sample']])}{phase(I['tests_frameworks'], 'PBXFrameworksBuildPhase', [I['fw_in_tests']])}/* End PBXFrameworksBuildPhase section */

/* Begin PBXGroup section */
		{I['main']} = {{
			isa = PBXGroup;
			children = (
				{I['fw_group']},
				{I['sample_group']},
				{I['tests_group']},
				{I['frameworks_group']},
				{I['products']},
			);
			sourceTree = "<group>";
		}};
		{I['frameworks_group']} /* Frameworks */ = {{
			isa = PBXGroup;
			children = (
				{I['core_xcf']},
			);
			path = Frameworks;
			sourceTree = "<group>";
		}};
		{I['products']} /* Products */ = {{
			isa = PBXGroup;
			children = (
				{I['fw_product']},
				{I['sample_product']},
				{I['tests_product']},
			);
			name = Products;
			sourceTree = "<group>";
		}};
/* End PBXGroup section */

/* Begin PBXHeadersBuildPhase section */
{phase(I['fw_headers'], 'PBXHeadersBuildPhase', [])}/* End PBXHeadersBuildPhase section */

/* Begin PBXNativeTarget section */
{target(I['fw_target'], 'FrameKit', I['fw_cfgs'], [I['fw_headers'], I['fw_sources'], I['fw_frameworks'], I['fw_resources']], [], [I['fw_group']], I['fw_product'], 'com.apple.product-type.framework')}{target(I['sample_target'], 'FrameKitSample', I['sample_cfgs'], [I['sample_sources'], I['sample_frameworks'], I['sample_resources'], I['sample_embed']], [I['fw_dep']], [I['sample_group']], I['sample_product'], 'com.apple.product-type.application')}{target(I['tests_target'], 'FrameKitTests', I['tests_cfgs'], [I['tests_sources'], I['tests_frameworks'], I['tests_resources']], [I['tests_dep']], [I['tests_group']], I['tests_product'], 'com.apple.product-type.bundle.unit-test')}/* End PBXNativeTarget section */

/* Begin PBXProject section */
		{I['project']} /* Project object */ = {{
			isa = PBXProject;
			attributes = {{
				BuildIndependentTargetsInParallel = 1;
				LastSwiftUpdateCheck = 2700;
				LastUpgradeCheck = 2700;
			}};
			buildConfigurationList = {I['proj_cfgs']};
			developmentRegion = en;
			hasScannedForEncodings = 0;
			knownRegions = (
				en,
				Base,
				ko,
				ja,
				"zh-Hans",
				"zh-Hant",
				vi,
				th,
				id,
				ru,
				es,
				"pt-BR",
				fr,
				de,
			);
			mainGroup = {I['main']};
			minimizedProjectReferenceProxies = 1;
			preferredProjectObjectVersion = 77;
			productRefGroup = {I['products']};
			projectDirPath = "";
			projectRoot = "";
			targets = (
				{I['fw_target']},
				{I['sample_target']},
				{I['tests_target']},
			);
		}};
/* End PBXProject section */

/* Begin PBXResourcesBuildPhase section */
{phase(I['fw_resources'], 'PBXResourcesBuildPhase', [])}{phase(I['sample_resources'], 'PBXResourcesBuildPhase', [])}{phase(I['tests_resources'], 'PBXResourcesBuildPhase', [])}/* End PBXResourcesBuildPhase section */

/* Begin PBXSourcesBuildPhase section */
{phase(I['fw_sources'], 'PBXSourcesBuildPhase', [])}{phase(I['sample_sources'], 'PBXSourcesBuildPhase', [])}{phase(I['tests_sources'], 'PBXSourcesBuildPhase', [])}/* End PBXSourcesBuildPhase section */

/* Begin PBXTargetDependency section */
		{I['fw_dep']} = {{
			isa = PBXTargetDependency;
			target = {I['fw_target']};
			targetProxy = {I['sample_proxy']};
		}};
		{I['tests_dep']} = {{
			isa = PBXTargetDependency;
			target = {I['fw_target']};
			targetProxy = {I['tests_proxy']};
		}};
/* End PBXTargetDependency section */

/* Begin XCBuildConfiguration section */
{cfg(I['proj_debug'], 'Debug', proj_debug)}{cfg(I['proj_release'], 'Release', proj_release)}{cfg(I['fw_debug'], 'Debug', fw)}{cfg(I['fw_release'], 'Release', fw)}{cfg(I['sample_debug'], 'Debug', sample)}{cfg(I['sample_release'], 'Release', sample)}{cfg(I['tests_debug'], 'Debug', tests)}{cfg(I['tests_release'], 'Release', tests)}/* End XCBuildConfiguration section */

/* Begin XCConfigurationList section */
"""
for lst, d, r in [('proj_cfgs','proj_debug','proj_release'),('fw_cfgs','fw_debug','fw_release'),('sample_cfgs','sample_debug','sample_release'),('tests_cfgs','tests_debug','tests_release')]:
    s += f"""		{I[lst]} = {{
			isa = XCConfigurationList;
			buildConfigurations = (
				{I[d]},
				{I[r]},
			);
			defaultConfigurationIsVisible = 0;
			defaultConfigurationName = Release;
		}};
"""
s += f"""/* End XCConfigurationList section */
	}};
	rootObject = {I['project']};
}}
"""
os.makedirs(os.path.dirname(out), exist_ok=True)
open(out, 'w').write(s)

# 공유 스킴
sd = os.path.join(os.path.dirname(out), 'xcshareddata', 'xcschemes')
os.makedirs(sd, exist_ok=True)
def ref(tid, name, product):
    return f"""<BuildableReference BuildableIdentifier = "primary" BlueprintIdentifier = "{tid}" BuildableName = "{product}" BlueprintName = "{name}" ReferencedContainer = "container:FrameKit.xcodeproj"></BuildableReference>"""
fwref = ref(I['fw_target'], 'FrameKit', 'FrameKit.framework')
tref = ref(I['tests_target'], 'FrameKitTests', 'FrameKitTests.xctest')
sref = ref(I['sample_target'], 'FrameKitSample', 'FrameKitSample.app')
open(os.path.join(sd, 'FrameKit.xcscheme'), 'w').write(f"""<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion = "2700" version = "1.7">
   <BuildAction parallelizeBuildables = "YES" buildImplicitDependencies = "YES">
      <BuildActionEntries>
         <BuildActionEntry buildForTesting = "YES" buildForRunning = "YES" buildForProfiling = "YES" buildForArchiving = "YES" buildForAnalyzing = "YES">
            {fwref}
         </BuildActionEntry>
      </BuildActionEntries>
   </BuildAction>
   <TestAction buildConfiguration = "Debug" selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv = "YES">
      <Testables>
         <TestableReference skipped = "NO">
            {tref}
         </TestableReference>
      </Testables>
   </TestAction>
   <LaunchAction buildConfiguration = "Debug" selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB" launchStyle = "0" useCustomWorkingDirectory = "NO" ignoresPersistentStateOnLaunch = "NO" debugDocumentVersioning = "YES" debugServiceExtension = "internal" allowLocationSimulation = "YES">
   </LaunchAction>
   <ArchiveAction buildConfiguration = "Release" revealArchiveInOrganizer = "YES">
   </ArchiveAction>
</Scheme>
""")
open(os.path.join(sd, 'FrameKitSample.xcscheme'), 'w').write(f"""<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion = "2700" version = "1.7">
   <BuildAction parallelizeBuildables = "YES" buildImplicitDependencies = "YES">
      <BuildActionEntries>
         <BuildActionEntry buildForTesting = "YES" buildForRunning = "YES" buildForProfiling = "YES" buildForArchiving = "YES" buildForAnalyzing = "YES">
            {sref}
         </BuildActionEntry>
      </BuildActionEntries>
   </BuildAction>
   <TestAction buildConfiguration = "Debug" selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv = "YES">
   </TestAction>
   <LaunchAction buildConfiguration = "Debug" selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB" launchStyle = "0" useCustomWorkingDirectory = "NO" ignoresPersistentStateOnLaunch = "NO" debugDocumentVersioning = "YES" debugServiceExtension = "internal" allowLocationSimulation = "YES">
      <BuildableProductRunnable runnableDebuggingMode = "0">
         {sref}
      </BuildableProductRunnable>
   </LaunchAction>
   <ArchiveAction buildConfiguration = "Release" revealArchiveInOrganizer = "YES">
   </ArchiveAction>
</Scheme>
""")
print("ok")
