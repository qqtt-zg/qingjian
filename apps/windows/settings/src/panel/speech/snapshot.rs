//! 辅助程序返回的界面快照；不包含发布凭证。

#[derive(Default)]
pub(super) struct Snapshot {
    pub(super) running: bool,

    pub(super) enabled: bool,

    pub(super) connected: bool,

    pub(super) status: String,

    pub(super) url: String,

    pub(super) qr: String,

    pub(super) choices: Vec<(String, String)>,

    pub(super) selected: String,
}

impl Snapshot {
    pub(super) fn parse(text: &str) -> Result<Self, String> {
        let document = text
            .parse::<toml_edit::DocumentMut>()
            .map_err(|e| e.to_string())?;
        let string = |key: &str| {
            document
                .get(key)
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_owned()
        };
        let boolean = |key: &str| document.get(key).and_then(|v| v.as_bool()).unwrap_or(false);
        let choices = document
            .get("choices")
            .and_then(|v| v.as_array_of_tables())
            .map(|rows| {
                rows.iter()
                    .filter_map(|row| {
                        Some((
                            row.get("key")?.as_str()?.to_owned(),
                            row.get("label")?.as_str()?.to_owned(),
                        ))
                    })
                    .collect()
            })
            .unwrap_or_default();
        Ok(Self {
            running: boolean("running"),
            enabled: boolean("enabled"),
            connected: boolean("phone_connected"),
            status: string("status"),
            url: string("pair_url"),
            qr: string("qr_path"),
            choices,
            selected: string("selected"),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::Snapshot;

    #[test]
    fn reads_native_control_snapshot_and_escaped_paths() {
        let snapshot=Snapshot::parse("running = true\nenabled = false\nphone_connected = true\nstatus = \"正在接收音色 50%\"\nqr_path = \"C:\\\\Users\\\\admin\\\\qr.png\"\nselected = \"builtin:1\"\n[[choices]]\nkey = \"builtin:1\"\nlabel = \"内置 · 女声 1\"\n").unwrap();
        assert!(snapshot.running && snapshot.connected && !snapshot.enabled);
        assert_eq!(snapshot.choices[0].0, snapshot.selected);
        assert_eq!(snapshot.qr, r"C:\Users\admin\qr.png");
        assert!(snapshot.status.contains("50%"));
    }
}
