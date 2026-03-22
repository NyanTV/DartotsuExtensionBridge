import 'dart:io';

import 'package:dartotsu_extension_bridge/Settings/Settings.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';
import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:isar_community/isar.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import 'Services/Aniyomi/AniyomiExtensions.dart';
import 'ExtensionManager.dart';
import 'Services/Mangayomi/Eval/dart/model/source_preference.dart';
import 'Services/Mangayomi/MangayomiExtensions.dart';
import 'Services/Mangayomi/Models/Source.dart';
import 'Settings/KvStore.dart';

late Isar isar;
WebViewEnvironment? webViewEnvironment;

class DartotsuExtensionBridge {
  Future<void> init(Isar? isarInstance, String dirName) async {
    var document = await getDatabaseDirectory(dirName);
    if (isarInstance == null) {
      isar = Isar.openSync([
        MSourceSchema,
        SourcePreferenceSchema,
        SourcePreferenceStringValueSchema,
        BridgeSettingsSchema,
        KvEntrySchema,
      ], directory: p.join(document.path, 'isar'));
    } else {
      isar = isarInstance;
    }
    final settings = await isar.bridgeSettings
        .filter()
        .idEqualTo(26)
        .findFirst();
    if (settings == null) {
      isar.writeTxnSync(
        () => isar.bridgeSettings.putSync(BridgeSettings()..id = 26),
      );
    }

    if (Platform.isAndroid) {
      Get.put(AniyomiExtensions(), tag: 'AniyomiExtensions');
    }
    Get.put(MangayomiExtensions(), tag: 'MangayomiExtensions');
    Get.put(ExtensionManager());
    if (Platform.isWindows) {
      final availableVersion = await WebViewEnvironment.getAvailableVersion();
      if (availableVersion != null) {
        webViewEnvironment = await WebViewEnvironment.create(
          settings: WebViewEnvironmentSettings(
            userDataFolder: p.join(document.path, 'flutter_inappwebview'),
          ),
        );
      }
    }
  }

  static void Function(String log, bool show) onLog = (log, _) {
    debugPrint('AnymeXExtensionBridge: $log');
  };
}

Future<Directory> getDatabaseDirectory(String dirName) async {
  final dir = await getApplicationDocumentsDirectory();
  if (Platform.isAndroid || Platform.isIOS || Platform.isMacOS) {
    return dir;
  } else {
    String dbDir = p.join(dir.path, dirName, 'databases');
    await Directory(dbDir).create(recursive: true);
    return Directory(dbDir);
  }
}
