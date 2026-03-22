class Repo {
  final String url;
  final String managerId;
  final String? name;
  final String? iconUrl;
  final String? extensions;

  const Repo({
    required this.url,
    required this.managerId,
    this.name,
    this.iconUrl,
    this.extensions,
  });

  factory Repo.fromJson(Map<String, dynamic> json) => Repo(
    url: json['url'] as String,
    managerId: json['managerId'] as String,
    name: json['name'] as String?,
    iconUrl: json['iconUrl'] as String?,
    extensions: json['extensions'] as String?,
  );

  Map<String, dynamic> toJson() => {
    'url': url,
    'managerId': managerId,
    if (name != null) 'name': name,
    if (iconUrl != null) 'iconUrl': iconUrl,
    if (extensions != null) 'extensions': extensions,
  };
}
