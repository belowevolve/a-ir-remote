.method private static airHomeMetadata(Landroid/content/ContentValues;Lmov/film/TvRows$Tile;)V
    .locals 4
    :try_start_airhome
    new-instance v0, Lorg/json/JSONObject;
    invoke-direct {v0}, Lorg/json/JSONObject;-><init>()V
    const-string v1, "v"
    const/4 v2, 0x1
    invoke-virtual {v0, v1, v2}, Lorg/json/JSONObject;->put(Ljava/lang/String;I)Lorg/json/JSONObject;
    const-string v1, "poster"
    iget-object v2, p1, Lmov/film/TvRows$Tile;->poster:Ljava/lang/String;
    invoke-virtual {v0, v1, v2}, Lorg/json/JSONObject;->put(Ljava/lang/String;Ljava/lang/Object;)Lorg/json/JSONObject;
    const-string v1, "mark"
    iget-object v2, p1, Lmov/film/TvRows$Tile;->mark:Ljava/lang/String;
    invoke-virtual {v0, v1, v2}, Lorg/json/JSONObject;->put(Ljava/lang/String;Ljava/lang/Object;)Lorg/json/JSONObject;
    const-string v1, "season"
    iget-object v2, p1, Lmov/film/TvRows$Tile;->seasonMark:Ljava/lang/String;
    invoke-virtual {v0, v1, v2}, Lorg/json/JSONObject;->put(Ljava/lang/String;Ljava/lang/Object;)Lorg/json/JSONObject;
    const-string v1, "news"
    iget-object v2, p1, Lmov/film/TvRows$Tile;->news:Ljava/lang/String;
    invoke-virtual {v0, v1, v2}, Lorg/json/JSONObject;->put(Ljava/lang/String;Ljava/lang/Object;)Lorg/json/JSONObject;
    const-string v1, "poster_art_uri"
    invoke-virtual {p0, v1}, Landroid/content/ContentValues;->getAsString(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v2
    if-eqz v2, :done_airhome
    invoke-static {v2}, Landroid/net/Uri;->parse(Ljava/lang/String;)Landroid/net/Uri;
    move-result-object v2
    invoke-virtual {v2}, Landroid/net/Uri;->buildUpon()Landroid/net/Uri$Builder;
    move-result-object v2
    invoke-virtual {v0}, Lorg/json/JSONObject;->toString()Ljava/lang/String;
    move-result-object v0
    const-string v3, "airhome_badges"
    invoke-virtual {v2, v3, v0}, Landroid/net/Uri$Builder;->appendQueryParameter(Ljava/lang/String;Ljava/lang/String;)Landroid/net/Uri$Builder;
    invoke-virtual {v2}, Landroid/net/Uri$Builder;->build()Landroid/net/Uri;
    move-result-object v2
    invoke-virtual {v2}, Landroid/net/Uri;->toString()Ljava/lang/String;
    move-result-object v2
    invoke-virtual {p0, v1, v2}, Landroid/content/ContentValues;->put(Ljava/lang/String;Ljava/lang/String;)V
    :try_end_airhome
    .catch Ljava/lang/Exception; {:try_start_airhome .. :try_end_airhome} :catch_airhome
    :done_airhome
    return-void
    :catch_airhome
    move-exception v0
    return-void
.end method
