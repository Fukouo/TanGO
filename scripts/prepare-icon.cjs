// Mechanical Android density export. The mascot artwork is generated separately.
const sharp = require('sharp');
const fs = require('node:fs/promises');
const path = require('node:path');
async function main() {
  const root = path.resolve(__dirname, '..');
  const source = path.resolve(process.argv[2]);
  const res = path.join(root, 'app/src/main/res');
  const asset = path.join(root, 'assets/branding');
  await fs.mkdir(asset, {recursive:true});
  await fs.mkdir(path.join(res, 'drawable-nodpi'), {recursive:true});
  await sharp(source).resize(768,768,{fit:'contain',background:'#00000000'}).png().toFile(path.join(res,'drawable-nodpi/tango_mascot.png'));
  const icon = await sharp(source).resize(512,512,{fit:'contain',background:'#00000000'}).flatten({background:'#f7f6f0'}).png().toBuffer();
  for (const [density,size] of Object.entries({mdpi:48,hdpi:72,xhdpi:96,xxhdpi:144,xxxhdpi:192})) {
    const folder=path.join(res,`mipmap-${density}`);await fs.mkdir(folder,{recursive:true});
    await sharp(icon).resize(size,size).png().toFile(path.join(folder,'ic_launcher.png'));
    const mask=Buffer.from(`<svg width="${size}" height="${size}"><circle cx="${size/2}" cy="${size/2}" r="${size/2}" fill="white"/></svg>`);
    await sharp(icon).resize(size,size).composite([{input:mask,blend:'dest-in'}]).png().toFile(path.join(folder,'ic_launcher_round.png'));
  }
  const inset = await sharp(source).resize(348,348,{fit:'contain',background:'#00000000'}).png().toBuffer();
  const rounded=Buffer.from('<svg width="512" height="512"><rect width="512" height="512" rx="112" fill="white"/></svg>');
  const preview=await sharp({create:{width:512,height:512,channels:4,background:'#f7f6f0'}}).composite([{input:inset,left:82,top:82}]).png().toBuffer();
  await sharp(preview).composite([{input:rounded,blend:'dest-in'}]).png().toFile(path.join(asset,'TanGO-icon-preview.png'));
  const circle=Buffer.from('<svg width="512" height="512"><circle cx="256" cy="256" r="256" fill="white"/></svg>');
  await sharp(preview).composite([{input:circle,blend:'dest-in'}]).png().toFile(path.join(asset,'TanGO-icon-round-preview.png'));
  const meta=await sharp(source).metadata();
  console.log(JSON.stringify({sourceSize:[meta.width,meta.height],hasAlpha:meta.hasAlpha,preview:'assets/branding/TanGO-icon-preview.png',densities:5}));
}
main().catch(error=>{console.error(error.message);process.exitCode=1;});
