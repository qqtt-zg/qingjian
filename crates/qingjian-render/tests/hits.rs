//! 候选窗口的点击区域：每个候选一块、每条译词一块，点在哪块上就是哪个目标；各内置主题、横竖排都要有。

mod scenes;

use qingjian_render::{
    FontLibrary, Frame, HitRegion, HitTarget, Layout, Renderer, Row, Theme, Tone,
};

fn region(hits: &[HitRegion], target: HitTarget) -> HitRegion {
    *hits
        .iter()
        .find(|region| region.target == target)
        .unwrap_or_else(|| panic!("没有 {target:?} 的点击区域：{hits:?}"))
}

fn center(region: HitRegion, scale: f32) -> (f32, f32) {
    (
        (region.x + region.width / 2.0) / scale,
        (region.y + region.height / 2.0) / scale,
    )
}

#[test]
fn candidates_and_their_translations_are_clickable() {
    let Ok(library) = FontLibrary::system("zh-CN") else {
        eprintln!("没有系统字体，跳过");
        return;
    };
    let mut renderer = Renderer::new(library);
    let frame = scenes::nihao();
    let themes = [
        ("qingjian", Theme::light()),
        ("system-blue", Theme::builtin("system-blue", false).unwrap()),
        ("wechat", Theme::builtin("wechat", false).unwrap()),
        ("sakura", Theme::builtin("sakura", false).unwrap()),
    ];
    for (name, theme) in &themes {
        for layout in [Layout::Vertical, Layout::Horizontal] {
            let rendered = renderer.render(&frame, layout, theme, 2.0).unwrap();
            let hits = &rendered.hits;
            let what = format!("{name} {layout:?}");
            for i in 0..frame.rows.len() {
                region(hits, HitTarget::Candidate(i));
            }
            // 「你好」有两条译词 hello · hi；主题只画第一条时第二条不可点
            let first = region(
                hits,
                HitTarget::Translation {
                    candidate: 0,
                    sense: 0,
                },
            );
            let (x, y) = center(first, rendered.scale);
            assert_eq!(rendered.hit_at(x, y), Some(first.target), "{what}");
            let second = hits.iter().find(|region| {
                region.target
                    == HitTarget::Translation {
                        candidate: 0,
                        sense: 1,
                    }
            });
            if let Some(&second) = second {
                assert!(
                    first.x + first.width <= second.x,
                    "{what}: {first:?} {second:?}"
                );
                let (x, y) = center(second, rendered.scale);
                assert_eq!(rendered.hit_at(x, y), Some(second.target), "{what}");
            }
            // 候选本体：从候选区域左边往里一点，落在序号 / 词上
            let word = region(hits, HitTarget::Candidate(1));
            let (x, y) = (
                (word.x + 2.0) / rendered.scale,
                (word.y + word.height / 2.0) / rendered.scale,
            );
            assert_eq!(
                rendered.hit_at(x, y),
                Some(HitTarget::Candidate(1)),
                "{what}"
            );
            assert_eq!(rendered.hit_at(-1.0, -1.0), None, "{what}");
        }
    }
}

#[test]
fn speaker_has_its_own_hit_region_without_covering_translation() {
    let library = FontLibrary::system("zh-CN").expect("需要系统字体验证喇叭布局");
    let mut renderer = Renderer::new(library);
    let mut frame = scenes::nihao();
    frame.rows[0].speakable = true;
    for dark in [false, true] {
        let theme = if dark { Theme::dark() } else { Theme::light() };
        for scale in [1.0, 1.5, 2.0] {
            for layout in [Layout::Vertical, Layout::Horizontal] {
                let rendered = renderer.render(&frame, layout, &theme, scale).unwrap();
                let speaker = region(&rendered.hits, HitTarget::Speak(0));
                let (x, y) = center(speaker, scale);
                assert_eq!(rendered.hit_at(x, y), Some(HitTarget::Speak(0)));
                assert!(speaker.width >= 18.0 * scale);
                assert!(speaker.height >= 16.0 * scale);
                let mut hovered = rendered.clone();
                hovered.speaker_feedback(0, false, dark);
                let mut pressed = rendered.clone();
                pressed.speaker_feedback(0, true, dark);
                assert_ne!(hovered.pixmap.data(), rendered.pixmap.data());
                assert_ne!(pressed.pixmap.data(), hovered.pixmap.data());
                assert_eq!(hovered.hits, rendered.hits);
                assert_eq!(
                    pressed.content_size_points(),
                    rendered.content_size_points()
                );
                let translation = region(
                    &rendered.hits,
                    HitTarget::Translation {
                        candidate: 0,
                        sense: 0,
                    },
                );
                assert!(translation.x + translation.width <= speaker.x);
                let (x, y) = center(translation, scale);
                assert_eq!(rendered.hit_at(x, y), Some(translation.target));
                assert!(
                    !rendered
                        .hits
                        .iter()
                        .any(|hit| hit.target == HitTarget::Speak(1))
                );
            }
        }
    }
}

#[test]
fn english_speaker_is_clickable_even_without_annotation() {
    let library = FontLibrary::system("zh-CN").expect("需要系统字体验证喇叭布局");
    let mut renderer = Renderer::new(library);
    for annotation in [vec![("控制".to_owned(), Tone::Gloss)], Vec::new()] {
        let mut row = Row::plain(0, "control");
        row.annotation = annotation;
        row.speakable = true;
        let frame = Frame {
            rows: vec![row, Row::plain(1, "🎛️")],
            highlighted: Some(0),
            ..Frame::default()
        };
        for name in [
            "qingjian",
            "system-blue",
            "wechat",
            "sakura",
            "miku",
            "bocchi",
            "dafeiyu",
        ] {
            for dark in [false, true] {
                let theme = Theme::builtin(name, dark).unwrap();
                for layout in [Layout::Vertical, Layout::Horizontal] {
                    let rendered = renderer.render(&frame, layout, &theme, 1.5).unwrap();
                    let speaker = region(&rendered.hits, HitTarget::Speak(0));
                    assert_eq!(
                        rendered
                            .hits
                            .iter()
                            .filter(|hit| hit.target == HitTarget::Speak(0))
                            .count(),
                        1
                    );
                    let (x, y) = center(speaker, rendered.scale);
                    assert_eq!(rendered.hit_at(x, y), Some(HitTarget::Speak(0)));
                    assert!(speaker.width >= 18.0 * rendered.scale);
                    assert!(speaker.height >= 16.0 * rendered.scale);
                    assert!(
                        !rendered
                            .hits
                            .iter()
                            .any(|hit| hit.target == HitTarget::Speak(1))
                    );
                }
            }
        }
    }
}

#[test]
fn menu_items_stack_top_to_bottom_and_take_the_hover() {
    let Ok(library) = FontLibrary::system("zh-CN") else {
        eprintln!("没有系统字体，跳过");
        return;
    };
    let mut renderer = Renderer::new(library);
    let items = ["置顶", "降权", "删除"];
    let rendered = renderer.render_menu(&items, Some(1), false, 2.0).unwrap();
    let rows: Vec<_> = (0..items.len())
        .map(|i| region(&rendered.hits, HitTarget::MenuItem(i)))
        .collect();
    assert!(
        rows.windows(2)
            .all(|pair| pair[0].y + pair[0].height <= pair[1].y + 0.5)
    );
    let (x, y) = center(rows[2], rendered.scale);
    assert_eq!(rendered.hit_at(x, y), Some(HitTarget::MenuItem(2)));
    let (width, height) = rendered.content_size_points();
    assert!(width >= 120.0 && height > 3.0 * 20.0, "{width}×{height}");
}
