use std::env;
use std::fs;
use std::path::Path;

fn main() {
    let project_dir = env::var("CARGO_MANIFEST_DIR").unwrap();
    let project_dir = Path::new(&project_dir);

    // Read version from gradle.properties
    let gradle_properties_path = project_dir.join("..").join("gradle.properties");
    println!("cargo:rerun-if-changed={}", gradle_properties_path.display());

    if let Ok(content) = fs::read_to_string(&gradle_properties_path) {
        for line in content.lines() {
            if let Some(version) = line.strip_prefix("mod_version=") {
                let version = version.trim();
                println!("cargo:rustc-env=CARGO_PKG_VERSION={}", version);
                break;
            }
        }
    }
}
