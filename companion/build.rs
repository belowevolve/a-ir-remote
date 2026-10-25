fn main() {
    println!("cargo:rerun-if-changed=assets/air-remote.ico");
    println!("cargo:rerun-if-changed=assets/app.manifest");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") {
        let mut resource = winresource::WindowsResource::new();
        resource.set_icon("assets/air-remote.ico");
        resource.set_manifest_file("assets/app.manifest");
        resource.set("ProductName", "Air Remote");
        resource.set("FileDescription", "Air Remote Companion");
        resource.compile().expect("Windows icon resource");
    }
}
