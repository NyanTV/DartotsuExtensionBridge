import 'package:dartotsu_extension_bridge/dartotsu_extension_bridge.dart';
import 'package:get/get.dart';
import 'package:hive/hive.dart';

import 'MangayomiExtensionManager.dart';
import 'MangayomiSourceMethods.dart';

class MangayomiExtensions extends Extension {
  @override
  String get id => 'mangayomi';

  @override
  String get name => 'Mangayomi';

  @override
  SourceMethods createSourceMethods(Source source) =>
      MangayomiSourceMethods(source);

  Box get _box => Hive.box('themeData');

  MangayomiExtensions() {
    initialize();
  }

  final _manager = Get.put(MangayomiExtensionManager());

  @override
  Future<void> initialize() async {
    if (isInitialized.value) return;
    isInitialized.value = true;

    await Future.wait([
      getInstalledAnimeExtensions(),
      getInstalledMangaExtensions(),
      getInstalledNovelExtensions(),
      fetchAvailableAnimeExtensions(_loadRepos(ItemType.anime)),
      fetchAvailableMangaExtensions(_loadRepos(ItemType.manga)),
      fetchAvailableNovelExtensions(_loadRepos(ItemType.novel)),
    ]);
  }

  List<String> _loadRepos(ItemType type) {
    final raw =
        _box.get('mangayomi${type.name}Repos', defaultValue: <dynamic>[])
            as List;
    return raw.cast<String>();
  }

  void _saveRepos(List<String> repos, ItemType type) {
    _box.put('mangayomi${type.name}Repos', repos);
  }

  @override
  Future<List<Source>> fetchAvailableAnimeExtensions(List<String>? repos) =>
      _fetchAvailable(ItemType.anime, repos);

  @override
  Future<List<Source>> fetchAvailableMangaExtensions(List<String>? repos) =>
      _fetchAvailable(ItemType.manga, repos);

  @override
  Future<List<Source>> fetchAvailableNovelExtensions(List<String>? repos) =>
      _fetchAvailable(ItemType.novel, repos);

  Future<List<Source>> _fetchAvailable(
    ItemType type,
    List<String>? repos,
  ) async {
    final repoList = repos ?? [];
    _saveRepos(repoList, type);

    final sources = await _manager.fetchAvailableExtensionsStream(
      type,
      repoList.isEmpty ? null : repoList,
    );
    final installedIds = getInstalledRx(type).value.map((e) => e.id).toSet();

    final list = sources
        .map((e) {
          var map = e.toJson();
          map['extensionType'] = 0;
          map["id"] = e.sourceId;
          return Source.fromJson(map);
        })
        .where((s) => !installedIds.contains(s.id))
        .toList();

    getAvailableRx(type).value = list;
    checkForUpdates(type);
    return list;
  }

  @override
  Future<void> addRepo(String repoUrl, ItemType type) async {
    final repos = _loadRepos(type);
    if (repos.contains(repoUrl)) return;
    final updated = [...repos, repoUrl];
    await _fetchAvailable(type, updated);
    getReposRx(type).value = updated
        .map((url) => Repo(url: url, managerId: id))
        .toList();
  }

  @override
  Future<void> removeRepo(String repoUrl, ItemType type) async {
    final repos = _loadRepos(type).where((r) => r != repoUrl).toList();
    await _fetchAvailable(type, repos);
    getReposRx(type).value = repos
        .map((url) => Repo(url: url, managerId: id))
        .toList();
  }

  @override
  Rx<List<Repo>> getReposRx(ItemType type) {
    final repos = _loadRepos(
      type,
    ).map((url) => Repo(url: url, managerId: id)).toList();
    return Rx<List<Repo>>(repos);
  }

  @override
  Future<List<Source>> getInstalledAnimeExtensions() =>
      _getInstalled(ItemType.anime);

  @override
  Future<List<Source>> getInstalledMangaExtensions() =>
      _getInstalled(ItemType.manga);

  @override
  Future<List<Source>> getInstalledNovelExtensions() =>
      _getInstalled(ItemType.novel);

  Future<List<Source>> _getInstalled(ItemType type) async {
    final stream = _manager
        .getExtensionsStream(type)
        .map(
          (sources) => sources.map((s) {
            var map = s.toJson();
            map['extensionType'] = 0;
            map["id"] = s.sourceId;
            return Source.fromJson(map);
          }).toList(),
        )
        .asBroadcastStream();

    getInstalledRx(type).bindStream(stream);
    return stream.first;
  }

  @override
  Future<void> installSource(Source source) async {
    if (source.id?.isEmpty ?? true) {
      return Future.error('Source ID is required for installation.');
    }

    await _manager.installSource(source);

    final rx = getAvailableRx(source.itemType!);
    rx.value = rx.value.where((s) => s.id != source.id).toList();
  }

  @override
  Future<void> uninstallSource(Source source) async {
    if (source.id?.isEmpty ?? true) {
      return Future.error('Source ID is required for uninstallation.');
    }

    await _manager.uninstallSource(source);

    final type = source.itemType!;
    getInstalledRx(type).value = getInstalledRx(
      type,
    ).value.where((s) => s.id != source.id).toList();

    final avail = getAvailableRx(type);
    if (!avail.value.any((s) => s.id == source.id)) {
      avail.value = [...avail.value, source];
    }
  }

  @override
  Future<void> updateSource(Source source) async {
    if (source.id?.isEmpty ?? true) {
      return Future.error('Source ID is required for update.');
    }
    await _manager.updateSource(source);
  }

  Future<void> checkForUpdates(ItemType type) async {
    final availableMap = {for (var s in _getAvailableList(type)) s.id: s};

    final updated = getInstalledRx(type).value.map((installed) {
      final avail = availableMap[int.tryParse(installed.id ?? '')];
      if (avail != null &&
          installed.version != null &&
          avail.version != null &&
          compareVersions(installed.version!, avail.version!) < 0) {
        return installed
          ..hasUpdate = true
          ..versionLast = avail.version;
      }
      return installed;
    }).toList();

    getInstalledRx(type).value = updated;
  }

  List<MSource> _getAvailableList(ItemType type) {
    switch (type) {
      case ItemType.anime:
        return _manager.availableAnimeExtensions.value;
      case ItemType.manga:
        return _manager.availableMangaExtensions.value;
      case ItemType.novel:
        return _manager.availableNovelExtensions.value;
    }
  }
}
