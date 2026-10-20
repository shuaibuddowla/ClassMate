const fs = require("fs");
const { chromium } = require("./web/node_modules/playwright");

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  
  for (const mode of ["light", "dark"]) {
    const svgPath = `d:/Code & Scripts/ClassMate-main/web/public/chat-doodle-${mode}.svg`;
    const svgContent = fs.readFileSync(svgPath, "utf-8");
    
    // Render on transparent background at 720x720 (2x crispness)
    await page.setViewportSize({ width: 720, height: 720 });
    await page.setContent(`<!DOCTYPE html><html><body style="margin:0;padding:0;background:transparent;overflow:hidden;">
      <div style="width:720px;height:720px;">${svgContent.replace('width="360" height="360"', 'width="720" height="720"')}</div>
    </body></html>`);
    
    const outPng = `d:/Code & Scripts/ClassMate-main/app/src/main/res/drawable-nodpi/bg_doodle_tile_${mode}.png`;
    await page.screenshot({ path: outPng, omitBackground: true });
    console.log(`Rendered ${outPng} successfully`);
  }
  
  await browser.close();
})();
