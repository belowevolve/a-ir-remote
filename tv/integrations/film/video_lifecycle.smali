.method private airSuspendVideo()V
    .locals 4
    invoke-direct {p0}, @TYPE@->airCancelVideoRestore()V
    invoke-static {}, Lmov/film/airvideo/SurfaceRestore;->supported()Z
    move-result v0
    if-eqz v0, :done
    invoke-virtual {p0}, Landroid/app/Activity;->isInPictureInPictureMode()Z
    move-result v0
    if-nez v0, :done
    iget-object v0, p0, @TYPE@->player:Landroidx/media3/exoplayer/ExoPlayer;
    if-eqz v0, :done
    iget-object v1, p0, @TYPE@->airVideoParameters:Landroidx/media3/common/TrackSelectionParameters;
    if-nez v1, :done
    invoke-interface {v0}, Landroidx/media3/common/Player;->getTrackSelectionParameters()Landroidx/media3/common/TrackSelectionParameters;
    move-result-object v1
    iput-object v1, p0, @TYPE@->airVideoParameters:Landroidx/media3/common/TrackSelectionParameters;
    invoke-interface {v0}, Landroidx/media3/common/Player;->pause()V
    invoke-virtual {v1}, Landroidx/media3/common/TrackSelectionParameters;->buildUpon()Landroidx/media3/common/TrackSelectionParameters$Builder;
    move-result-object v1
    const/4 v2, 0x2
    const/4 v3, 0x1
    invoke-virtual {v1, v2, v3}, Landroidx/media3/common/TrackSelectionParameters$Builder;->setTrackTypeDisabled(IZ)Landroidx/media3/common/TrackSelectionParameters$Builder;
    invoke-virtual {v1}, Landroidx/media3/common/TrackSelectionParameters$Builder;->build()Landroidx/media3/common/TrackSelectionParameters;
    move-result-object v1
    invoke-interface {v0, v1}, Landroidx/media3/common/Player;->setTrackSelectionParameters(Landroidx/media3/common/TrackSelectionParameters;)V
    const-string v0, "FilmVideoLifecycle"
    const-string v1, "Video track suspended; original selection retained"
    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
    :done
    return-void
.end method

.method private airRestoreVideo()V
    .locals 5
    iget-object v1, p0, @TYPE@->airVideoParameters:Landroidx/media3/common/TrackSelectionParameters;
    if-eqz v1, :done
    iget-object v0, p0, @TYPE@->player:Landroidx/media3/exoplayer/ExoPlayer;
    if-eqz v0, :clear
    iget-object v2, p0, @TYPE@->surface:Landroid/view/SurfaceView;
    if-eqz v2, :done
    invoke-virtual {v2}, Landroid/view/SurfaceView;->getHolder()Landroid/view/SurfaceHolder;
    move-result-object v3
    invoke-interface {v3}, Landroid/view/SurfaceHolder;->getSurface()Landroid/view/Surface;
    move-result-object v4
    invoke-virtual {v4}, Landroid/view/Surface;->isValid()Z
    move-result v4
    if-eqz v4, :wait_surface
    invoke-direct {p0}, @TYPE@->airCancelVideoRestore()V
    invoke-interface {v0, v2}, Landroidx/media3/common/Player;->setVideoSurfaceView(Landroid/view/SurfaceView;)V
    const-string v2, "FilmVideoLifecycle"
    const-string v3, "Display surface valid before enabling video"
    invoke-static {v2, v3}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
    invoke-interface {v0, v1}, Landroidx/media3/common/Player;->setTrackSelectionParameters(Landroidx/media3/common/TrackSelectionParameters;)V
    const-string v0, "FilmVideoLifecycle"
    const-string v1, "Original video selection restored"
    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
    goto :clear
    :wait_surface
    iget-object v4, p0, @TYPE@->airVideoCallback:Lmov/film/airvideo/SurfaceRestore;
    if-nez v4, :done
    new-instance v4, Lmov/film/airvideo/SurfaceRestore;
    invoke-direct {v4, p0}, Lmov/film/airvideo/SurfaceRestore;-><init>(Landroid/app/Activity;)V
    iput-object v4, p0, @TYPE@->airVideoCallback:Lmov/film/airvideo/SurfaceRestore;
    invoke-interface {v3, v4}, Landroid/view/SurfaceHolder;->addCallback(Landroid/view/SurfaceHolder$Callback;)V
    const-string v0, "FilmVideoLifecycle"
    const-string v1, "Waiting for real display surface before restoring video"
    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
    return-void
    :clear
    const/4 v0, 0x0
    iput-object v0, p0, @TYPE@->airVideoParameters:Landroidx/media3/common/TrackSelectionParameters;
    :done
    return-void
.end method

.method private airCancelVideoRestore()V
    .locals 2
    iget-object v0, p0, @TYPE@->airVideoCallback:Lmov/film/airvideo/SurfaceRestore;
    if-eqz v0, :done
    iget-object v1, p0, @TYPE@->surface:Landroid/view/SurfaceView;
    if-eqz v1, :clear
    invoke-virtual {v1}, Landroid/view/SurfaceView;->getHolder()Landroid/view/SurfaceHolder;
    move-result-object v1
    invoke-interface {v1, v0}, Landroid/view/SurfaceHolder;->removeCallback(Landroid/view/SurfaceHolder$Callback;)V
    :clear
    const/4 v0, 0x0
    iput-object v0, p0, @TYPE@->airVideoCallback:Lmov/film/airvideo/SurfaceRestore;
    :done
    return-void
.end method
