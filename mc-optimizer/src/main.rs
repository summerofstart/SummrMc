// ============================================================================
// main.rs — Standalone CLI for MC-NATIVE-OPT
// Most logic lives in lib.rs. This just calls it.
// ============================================================================

fn main() {
    // Forward to the library's standalone entry point
    mc_native_opt::standalone_main();
}