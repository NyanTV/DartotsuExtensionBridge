import 'package:get/get.dart';
import '../Models/Repo.dart';
import '../Models/Source.dart';
import 'SourceMethods.dart';

abstract class Extension extends GetxController {
  var isInitialized = false.obs;

  String get id;
  String get name;

  bool get supportsAnime => true;
  bool get supportsManga => true;
  bool get supportsNovel => true;

  SourceMethods createSourceMethods(Source source);

  final Rx<List<Source>> installedAnimeExtensions = Rx([]);
  final Rx<List<Source>> installedMangaExtensions = Rx([]);
  final Rx<List<Source>> installedNovelExtensions = Rx([]);
  final Rx<List<Source>> availableAnimeExtensions = Rx([]);
  final Rx<List<Source>> availableMangaExtensions = Rx([]);
  final Rx<List<Source>> availableNovelExtensions = Rx([]);

  final Rx<List<Source>> _rawAvailableAnime = Rx([]);
  final Rx<List<Source>> _rawAvailableManga = Rx([]);
  final Rx<List<Source>> _rawAvailableNovel = Rx([]);

  final Rx<List<Repo>> _reposAnime = Rx([]);
  final Rx<List<Repo>> _reposManga = Rx([]);
  final Rx<List<Repo>> _reposNovel = Rx([]);

  Set<String> get schemes => {};

  Future<void> handleSchemes(Uri uri) async {}

  Future<void> initialize() async {
    await fetchInstalledAnimeExtensions();
    await fetchInstalledMangaExtensions();
    await fetchInstalledNovelExtensions();
    await fetchAnimeExtensions();
    await fetchMangaExtensions();
    await fetchNovelExtensions();
    isInitialized.value = true;
  }

  Future<void> fetchAnimeExtensions() async {}
  Future<void> fetchMangaExtensions() async {}
  Future<void> fetchNovelExtensions() async {}

  Future<void> fetchInstalledAnimeExtensions() async {}
  Future<void> fetchInstalledMangaExtensions() async {}
  Future<void> fetchInstalledNovelExtensions() async {}

  Future<void> installSource(Source source);
  Future<void> uninstallSource(Source source);
  Future<void> updateSource(Source source);

  Future<void> addRepo(String repoUrl, ItemType type) async {}
  Future<void> removeRepo(String repoUrl, ItemType type) async {}

  Rx<List<Repo>> getReposRx(ItemType type) {
    switch (type) {
      case ItemType.anime:
        return _reposAnime;
      case ItemType.manga:
        return _reposManga;
      case ItemType.novel:
        return _reposNovel;
    }
  }

  Rx<List<Source>> getRawAvailableRx(ItemType type) {
    switch (type) {
      case ItemType.anime:
        return _rawAvailableAnime;
      case ItemType.manga:
        return _rawAvailableManga;
      case ItemType.novel:
        return _rawAvailableNovel;
    }
  }

  Rx<List<Source>> getAvailableRx(ItemType type) {
    switch (type) {
      case ItemType.anime:
        return availableAnimeExtensions;
      case ItemType.manga:
        return availableMangaExtensions;
      case ItemType.novel:
        return availableNovelExtensions;
    }
  }

  Rx<List<Source>> getInstalledRx(ItemType type) {
    switch (type) {
      case ItemType.anime:
        return installedAnimeExtensions;
      case ItemType.manga:
        return installedMangaExtensions;
      case ItemType.novel:
        return installedNovelExtensions;
    }
  }

  Rx<List<Source>> getSortedInstalledExtension(ItemType itemType) =>
      getInstalledRx(itemType);

  Future<List<Source>> getInstalledAnimeExtensions() => Future.value([]);
  Future<List<Source>> fetchAvailableAnimeExtensions(List<String>? repos) =>
      Future.value([]);
  Future<List<Source>> getInstalledMangaExtensions() => Future.value([]);
  Future<List<Source>> fetchAvailableMangaExtensions(List<String>? repos) =>
      Future.value([]);
  Future<List<Source>> getInstalledNovelExtensions() => Future.value([]);
  Future<List<Source>> fetchAvailableNovelExtensions(List<String>? repos) =>
      Future.value([]);

  Future<void> onRepoSaved(List<String> repoUrl, ItemType type) async {
    if (repoUrl.isEmpty) return;
    switch (type) {
      case ItemType.anime:
        await fetchAvailableAnimeExtensions(repoUrl);
        break;
      case ItemType.manga:
        await fetchAvailableMangaExtensions(repoUrl);
        break;
      case ItemType.novel:
        await fetchAvailableNovelExtensions(repoUrl);
        break;
    }
  }

  int compareVersions(String v1, String v2) {
    final a = v1.split('.').map(int.tryParse).toList();
    final b = v2.split('.').map(int.tryParse).toList();
    for (int i = 0; i < a.length || i < b.length; i++) {
      final n1 = i < a.length ? a[i] ?? 0 : 0;
      final n2 = i < b.length ? b[i] ?? 0 : 0;
      if (n1 != n2) return n1.compareTo(n2);
    }
    return 0;
  }
}
