// Rasterize the source-native SVG branding for Home Assistant / add-on packaging.
// Development utility: npm install --no-save sharp, or use the configured runtime.
const sharp=require('sharp'),fs=require('node:fs');
async function main(){
 const brand='custom_components/ble_studio/brand';fs.mkdirSync(brand,{recursive:true});
 for(const name of ['icon','dark_icon'])await sharp('dist/icon.svg').resize(256,256).png().toFile(`${brand}/${name}.png`);
 for(const name of ['logo','dark_logo'])await sharp('branding/logo.svg').resize(600,128).png().toFile(`${brand}/${name}.png`);
 await sharp('dist/icon.svg').resize(256,256).png().toFile('addon/icon.png');
 await sharp('branding/logo.svg').resize(600,128).png().toFile('addon/logo.png');
 console.log('Rendered Home Assistant and add-on brand assets.');
}main().catch(e=>{console.error(e);process.exitCode=1;});
