#!/usr/bin/env python3
"""
Icon Generator & Fallback Utility for Custom WebView Template
=============================================================

This script implements the intelligent icon fallback chain for generating
Android launcher icons. It follows a three-tier strategy:

  TIER 1: Custom Android / Apple Touch Icons
  TIER 2: Website Favicon extraction
  TIER 3: Auto-generated colored square with the first letter of the App Title

Usage:
  python3 generate_icons.py [--icon source.png] [--title "App Name"] [--favicon url]

Requirements:
  pip install Pillow requests
"""

import argparse
import os
import sys
import json
import math
from pathlib import Path

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    print("ERROR: Pillow is required. Install with: pip install Pillow")
    sys.exit(1)

# ── Android mipmap density mapping ──
DENSITY_MAP = {
    "mdpi":    48,
    "hdpi":    72,
    "xhdpi":   96,
    "xxhdpi":  144,
    "xxxhdpi": 192,
}

MIPMAP_BASE = "app/src/main/res"

# ── Vibrant color palette for auto-generated icons ──
PALETTE = [
    ("#667EEA", "#764BA2"),  # Indigo → Purple
    ("#F093FB", "#F5576C"),  # Pink → Coral
    ("#4FACFE", "#00F2FE"),  # Blue → Cyan
    ("#43E97B", "#38F9D7"),  # Green → Teal
    ("#FA709A", "#FEE140"),  # Pink → Yellow
    ("#A18CD1", "#FBC2EB"),  # Purple → Soft Pink
    ("#FF9A9E", "#FECFEF"),  # Soft Red → Blush
    ("#FFECD2", "#FCB69F"),  # Cream → Peach
]


def ensure_rgba(img):
    """Convert image to RGBA if needed."""
    if img.mode != "RGBA":
        return img.convert("RGBA")
    return img


def generate_fallback_icon(title, output_path, size=512):
    """
    TIER 3: Generate a colorful rounded-square icon with the first letter
    of the app title as the central element.
    """
    import hashlib

    # Pick a deterministic color based on the title
    idx = int(hashlib.md5(title.encode()).hexdigest(), 16) % len(PALETTE)
    color_start, color_end = PALETTE[idx]

    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # Draw rounded square (approximate with circles at corners and filled rects)
    radius = size // 8
    margin = size // 20

    # Simple gradient-filled rounded rectangle using circles + rects
    # Fill center
    draw.rectangle([margin + radius, margin, size - margin - radius, size - margin], fill=color_start)
    draw.rectangle([margin, margin + radius, size - margin, size - margin - radius], fill=color_start)

    # Corner circles
    draw.ellipse([margin, margin, margin + 2 * radius, margin + 2 * radius], fill=color_start)
    draw.ellipse([size - margin - 2 * radius, margin, size - margin, margin + 2 * radius], fill=color_start)
    draw.ellipse([margin, size - margin - 2 * radius, margin + 2 * radius, size - margin], fill=color_start)
    draw.ellipse([size - margin - 2 * radius, size - margin - 2 * radius, size - margin, size - margin], fill=color_start)

    # Draw first letter
    letter = title.strip()[0].upper() if title.strip() else "A"
    font_size = size // 2

    # Try to use a system font, fallback to default
    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", font_size)
    except (IOError, OSError):
        try:
            font = ImageFont.truetype("/usr/share/fonts/TTF/DejaVuSans-Bold.ttf", font_size)
        except (IOError, OSError):
            font = ImageFont.load_default()

    # Center the letter
    bbox = draw.textbbox((0, 0), letter, font=font)
    text_width = bbox[2] - bbox[0]
    text_height = bbox[3] - bbox[1]
    x = (size - text_width) / 2 - bbox[0]
    y = (size - text_height) / 2 - bbox[1]

    draw.text((x, y), letter, fill="white", font=font)

    img.save(output_path, "PNG", optimize=True)
    print(f"  ✓ Generated fallback icon: {output_path} ({size}x{size})")
    return img


def process_icon_source(source_path, output_dir, title="App"):
    """
    Main processing: take a source image (or generate fallback), resize to all
    Android mipmap densities, and place them in the correct resource folders.
    """
    if source_path and os.path.exists(source_path):
        print(f"[TIER 1/2] Using provided icon: {source_path}")
        img = Image.open(source_path)
        img = ensure_rgba(img)

        # Resize source to 512x512 as base
        if img.size != (512, 512):
            img = img.resize((512, 512), Image.LANCZOS)
            print(f"  Resized source to 512x512")
    else:
        print(f"[TIER 3] Generating fallback icon for: \"{title}\"")
        fallback_path = os.path.join(output_dir, "fallback_512.png")
        img = generate_fallback_icon(title, fallback_path, 512)

    # Generate all density variants
    print("\nGenerating mipmap density variants:")
    for density, size in DENSITY_MAP.items():
        folder = os.path.join(output_dir, f"mipmap-{density}")
        os.makedirs(folder, exist_ok=True)

        resized = img.resize((size, size), Image.LANCZOS)
        out_path = os.path.join(folder, "ic_launcher.png")
        resized.save(out_path, "PNG", optimize=True)

        # Also create round variant
        round_path = os.path.join(folder, "ic_launcher_round.png")
        resized.save(round_path, "PNG", optimize=True)

        print(f"  ✓ mipmap-{density}/ic_launcher.png ({size}x{size})")

    print("\n✅ All launcher icons generated successfully!")
    print(f"   Base path: {output_dir}/")


def extract_favicon(url, output_path):
    """
    TIER 2: Attempt to extract a favicon from a website URL.
    Tries common favicon locations.
    """
    try:
        import requests
        from urllib.parse import urljoin, urlparse
        from io import BytesIO

        parsed = urlparse(url)
        base = f"{parsed.scheme}://{parsed.netloc}"

        favicon_paths = [
            "/favicon.ico",
            "/favicon.png",
            "/apple-touch-icon.png",
            "/apple-touch-icon-precomposed.png",
        ]

        for fav_path in favicon_paths:
            fav_url = urljoin(base, fav_path)
            try:
                resp = requests.get(fav_url, timeout=5, headers={"User-Agent": "Mozilla/5.0"})
                if resp.status_code == 200:
                    img = Image.open(BytesIO(resp.content))
                    img = ensure_rgba(img)
                    img.save(output_path, "PNG", optimize=True)
                    print(f"  ✓ Extracted favicon from: {fav_url}")
                    return output_path
            except Exception:
                continue

        print(f"  ⚠ No favicon found for {base}")
    except ImportError:
        print("  ⚠ requests library not available. Install: pip install requests")

    return None


def main():
    parser = argparse.ArgumentParser(
        description="Custom WebView Icon Generator — Three-tier icon fallback",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python3 generate_icons.py --icon my-logo.png --title "My App"
  python3 generate_icons.py --title "Cool Browser"  
  python3 generate_icons.py --favicon https://example.com --title "Example"
        """
    )
    parser.add_argument("--icon", help="Path to source icon (TIER 1)")
    parser.add_argument("--title", default="App", help="App title for fallback generation (TIER 3)")
    parser.add_argument("--favicon", help="Website URL to extract favicon from (TIER 2)")
    parser.add_argument("--output", default=MIPMAP_BASE, help="Output base directory for mipmap folders")

    args = parser.parse_args()

    print("=" * 55)
    print("  Custom WebView Icon Generator")
    print("=" * 55)

    output_dir = os.path.abspath(args.output)
    os.makedirs(output_dir, exist_ok=True)

    # TIER 1: Use provided icon
    source = args.icon

    # TIER 2: Try favicon extraction if no icon provided
    if not source and args.favicon:
        fav_path = os.path.join(output_dir, "extracted_favicon.png")
        source = extract_favicon(args.favicon, fav_path)

    # TIER 3: Generate fallback (handled inside process_icon_source)
    process_icon_source(source, output_dir, args.title)


if __name__ == "__main__":
    main()